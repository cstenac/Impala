// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.planner;

import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.apache.kudu.ColumnSchema;
import org.apache.kudu.Schema;
import org.apache.kudu.client.KuduClient;
import org.apache.kudu.client.KuduClient.KuduClientBuilder;
import org.apache.kudu.client.KuduPredicate;
import org.apache.kudu.client.KuduPredicate.ComparisonOp;
import org.apache.kudu.client.KuduScanToken;
import org.apache.kudu.client.KuduScanToken.KuduScanTokenBuilder;
import org.apache.kudu.client.LocatedTablet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.impala.analysis.Analyzer;
import org.apache.impala.analysis.BinaryPredicate;
import org.apache.impala.analysis.BoolLiteral;
import org.apache.impala.analysis.Expr;
import org.apache.impala.analysis.LiteralExpr;
import org.apache.impala.analysis.NullLiteral;
import org.apache.impala.analysis.NumericLiteral;
import org.apache.impala.analysis.SlotDescriptor;
import org.apache.impala.analysis.SlotRef;
import org.apache.impala.analysis.StringLiteral;
import org.apache.impala.analysis.TupleDescriptor;
import org.apache.impala.catalog.KuduTable;
import org.apache.impala.common.ImpalaRuntimeException;
import org.apache.impala.thrift.TExplainLevel;
import org.apache.impala.thrift.TKuduScanNode;
import org.apache.impala.thrift.TNetworkAddress;
import org.apache.impala.thrift.TPlanNode;
import org.apache.impala.thrift.TPlanNodeType;
import org.apache.impala.thrift.TScanRange;
import org.apache.impala.thrift.TScanRangeLocation;
import org.apache.impala.thrift.TScanRangeLocations;
import com.google.common.base.Charsets;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Scan of a single Kudu table.
 *
 * Extracts predicates that can be pushed down to Kudu. Currently only binary predicates
 * that have a constant expression on one side and a slot ref on the other can be
 * evaluated by Kudu.
 *
 * Uses the Kudu ScanToken API to generate a set of Kudu "scan tokens" which are used for
 * scheduling and initializing the scanners. Scan tokens are opaque objects that represent
 * a scan for some Kudu data on a tablet (currently one token represents one tablet), and
 * it contains the tablet locations and all information needed to produce a Kudu scanner,
 * including the projected columns and predicates that are pushed down.
 *
 * After KUDU-1065 is resolved, Kudu will also prune the tablets that don't need to be
 * scanned, and only the tokens for those tablets will be returned.
 */
public class KuduScanNode extends ScanNode {
  private final static Logger LOG = LoggerFactory.getLogger(KuduScanNode.class);

  private final KuduTable kuduTable_;

  // Indexes for the set of hosts that will be used for the query.
  // From analyzer.getHostIndex().getIndex(address)
  private final Set<Integer> hostIndexSet_ = Sets.newHashSet();

  // List of conjuncts that can be pushed down to Kudu, after they have been normalized
  // by BinaryPredicate.normalizeSlotRefComparison(). Used for computing stats and
  // explain strings.
  private final List<Expr> kuduConjuncts_ = Lists.newArrayList();

  // Exprs in kuduConjuncts_ converted to KuduPredicates.
  private final List<KuduPredicate> kuduPredicates_ = Lists.newArrayList();

  public KuduScanNode(PlanNodeId id, TupleDescriptor desc) {
    super(id, desc, "SCAN KUDU");
    kuduTable_ = (KuduTable) desc_.getTable();
  }

  @Override
  public void init(Analyzer analyzer) throws ImpalaRuntimeException {
    assignConjuncts(analyzer);
    analyzer.createEquivConjuncts(tupleIds_.get(0), conjuncts_);
    conjuncts_ = orderConjunctsByCost(conjuncts_);

    try (KuduClient client =
         new KuduClientBuilder(kuduTable_.getKuduMasterAddresses()).build()) {
      org.apache.kudu.client.KuduTable rpcTable =
          client.openTable(kuduTable_.getKuduTableName());
      validateSchema(rpcTable);

      // Extract predicates that can be evaluated by Kudu.
      extractKuduConjuncts(analyzer, client, rpcTable);

      // Materialize the slots of the remaining conjuncts (i.e. those not pushed to Kudu)
      analyzer.materializeSlots(conjuncts_);

      // Creates Kudu scan tokens and sets the scan range locations.
      computeScanRangeLocations(analyzer, client, rpcTable);
    } catch (Exception e) {
      throw new ImpalaRuntimeException("Unable to initialize the Kudu scan node", e);
    }

    computeMemLayout(analyzer);
    computeStats(analyzer);
  }

  /**
   * Validate the columns Impala expects are actually in the Kudu table.
   */
  private void validateSchema(org.apache.kudu.client.KuduTable rpcTable)
      throws ImpalaRuntimeException {
    Schema tableSchema = rpcTable.getSchema();
    for (SlotDescriptor desc: getTupleDesc().getSlots()) {
      String colName = desc.getColumn().getName();
      try {
        tableSchema.getColumn(colName);
      } catch (Exception e) {
        throw new ImpalaRuntimeException("Column '" + colName + "' not found in kudu " +
            "table " + rpcTable.getName());
      }
    }
  }

  /**
   * Compute the scan range locations for the given table using the scan tokens.
   */
  private void computeScanRangeLocations(Analyzer analyzer,
      KuduClient client, org.apache.kudu.client.KuduTable rpcTable)
      throws ImpalaRuntimeException {
    scanRanges_ = Lists.newArrayList();

    List<KuduScanToken> scanTokens = createScanTokens(client, rpcTable);
    for (KuduScanToken token: scanTokens) {
      LocatedTablet tablet = token.getTablet();
      List<TScanRangeLocation> locations = Lists.newArrayList();
      if (tablet.getReplicas().isEmpty()) {
        throw new ImpalaRuntimeException(String.format(
            "At least one tablet does not have any replicas. Tablet ID: %s",
            new String(tablet.getTabletId(), Charsets.UTF_8)));
      }

      for (LocatedTablet.Replica replica: tablet.getReplicas()) {
        TNetworkAddress address =
            new TNetworkAddress(replica.getRpcHost(), replica.getRpcPort());
        // Use the network address to look up the host in the global list
        Integer hostIndex = analyzer.getHostIndex().getIndex(address);
        locations.add(new TScanRangeLocation(hostIndex));
        hostIndexSet_.add(hostIndex);
      }

      TScanRange scanRange = new TScanRange();
      try {
        scanRange.setKudu_scan_token(token.serialize());
      } catch (IOException e) {
        throw new ImpalaRuntimeException("Unable to serialize Kudu scan token=" +
            token.toString(), e);
      }

      TScanRangeLocations locs = new TScanRangeLocations();
      locs.setScan_range(scanRange);
      locs.locations = locations;
      scanRanges_.add(locs);
    }
  }

  /**
   * Returns KuduScanTokens for this scan given the projected columns and predicates that
   * will be pushed to Kudu.
   */
  private List<KuduScanToken> createScanTokens(KuduClient client,
      org.apache.kudu.client.KuduTable rpcTable) {
    List<String> projectedCols = Lists.newArrayList();
    for (SlotDescriptor desc: getTupleDesc().getSlots()) {
      if (desc.isMaterialized()) projectedCols.add(desc.getColumn().getName());
    }

    KuduScanTokenBuilder tokenBuilder = client.newScanTokenBuilder(rpcTable);
    tokenBuilder.setProjectedColumnNames(projectedCols);
    for (KuduPredicate predicate: kuduPredicates_) tokenBuilder.addPredicate(predicate);
    return tokenBuilder.build();
  }

  @Override
  protected double computeSelectivity() {
    List<Expr> allConjuncts = Lists.newArrayList(
        Iterables.concat(conjuncts_, kuduConjuncts_));
    return computeCombinedSelectivity(allConjuncts);
  }

  @Override
  protected void computeStats(Analyzer analyzer) {
    super.computeStats(analyzer);
    // Update the number of nodes to reflect the hosts that have relevant data.
    numNodes_ = hostIndexSet_.size();

    // Update the cardinality
    inputCardinality_ = cardinality_ = kuduTable_.getNumRows();
    cardinality_ *= computeSelectivity();
    cardinality_ = Math.min(Math.max(1, cardinality_), kuduTable_.getNumRows());
    cardinality_ = capAtLimit(cardinality_);
    LOG.debug("computeStats KuduScan: cardinality=" + Long.toString(cardinality_));
  }

  @Override
  protected String getNodeExplainString(String prefix, String detailPrefix,
      TExplainLevel detailLevel) {
    StringBuilder result = new StringBuilder();

    String aliasStr = desc_.hasExplicitAlias() ? " " + desc_.getAlias() : "";
    result.append(String.format("%s%s:%s [%s%s]\n", prefix, id_.toString(), displayName_,
        kuduTable_.getFullName(), aliasStr));

    switch (detailLevel) {
      case MINIMAL: break;
      case STANDARD: // Fallthrough intended.
      case EXTENDED: // Fallthrough intended.
      case VERBOSE: {
        if (!conjuncts_.isEmpty()) {
          result.append(detailPrefix + "predicates: " + getExplainString(conjuncts_)
              + "\n");
        }
        if (!kuduConjuncts_.isEmpty()) {
          result.append(detailPrefix + "kudu predicates: " + getExplainString(
              kuduConjuncts_) + "\n");
        }
      }
    }
    return result.toString();
  }

  @Override
  protected void toThrift(TPlanNode node) {
    node.node_type = TPlanNodeType.KUDU_SCAN_NODE;
    node.kudu_scan_node = new TKuduScanNode(desc_.getId().asInt());
  }

  /**
   * Extracts predicates from conjuncts_ that can be pushed down to Kudu. Currently only
   * binary predicates that have a constant expression on one side and a slot ref on the
   * other can be evaluated by Kudu. Only looks at comparisons of constants (i.e., the
   * bounds of the result can be evaluated with Expr::GetValue(NULL)). If a conjunct can
   * be converted into this form, the normalized expr is added to kuduConjuncts_, a
   * KuduPredicate is added to kuduPredicates_, and the original expr from conjuncts_ is
   * removed.
   */
  private void extractKuduConjuncts(Analyzer analyzer,
      KuduClient client, org.apache.kudu.client.KuduTable rpcTable) {
    ListIterator<Expr> it = conjuncts_.listIterator();
    while (it.hasNext()) {
      if (tryConvertKuduPredicate(analyzer, rpcTable, it.next())) it.remove();
    }
  }

  /**
   * If 'expr' can be converted to a KuduPredicate, returns true and updates
   * kuduPredicates_ and kuduConjuncts_.
   */
  private boolean tryConvertKuduPredicate(Analyzer analyzer,
      org.apache.kudu.client.KuduTable table, Expr expr) {
    if (!(expr instanceof BinaryPredicate)) return false;
    BinaryPredicate predicate = (BinaryPredicate) expr;

    // TODO KUDU-931 look into handling implicit/explicit casts on the SlotRef.
    predicate = BinaryPredicate.normalizeSlotRefComparison(predicate, analyzer);
    if (predicate == null) return false;
    ComparisonOp op = getKuduOperator(((BinaryPredicate)predicate).getOp());
    if (op == null) return false;

    SlotRef ref = (SlotRef) predicate.getChild(0);
    LiteralExpr literal = (LiteralExpr) predicate.getChild(1);

    // Cannot push prediates with null literal values (KUDU-1595).
    if (literal instanceof NullLiteral) return false;

    String colName = ref.getDesc().getColumn().getName();
    ColumnSchema column = table.getSchema().getColumn(colName);
    KuduPredicate kuduPredicate = null;
    switch (literal.getType().getPrimitiveType()) {
      case BOOLEAN: {
        kuduPredicate = KuduPredicate.newComparisonPredicate(column, op,
            ((BoolLiteral)literal).getValue());
        break;
      }
      case TINYINT:
      case SMALLINT:
      case INT: {
        kuduPredicate = KuduPredicate.newComparisonPredicate(column, op,
            ((NumericLiteral)literal).getIntValue());
        break;
      }
      case BIGINT: {
        kuduPredicate = KuduPredicate.newComparisonPredicate(column, op,
            ((NumericLiteral)literal).getLongValue());
        break;
      }
      case FLOAT: {
        kuduPredicate = KuduPredicate.newComparisonPredicate(column, op,
            (float)((NumericLiteral)literal).getDoubleValue());
        break;
      }
      case DOUBLE: {
        kuduPredicate = KuduPredicate.newComparisonPredicate(column, op,
            ((NumericLiteral)literal).getDoubleValue());
        break;
      }
      case STRING:
      case VARCHAR:
      case CHAR: {
        kuduPredicate = KuduPredicate.newComparisonPredicate(column, op,
            ((StringLiteral)literal).getStringValue());
        break;
      }
      default: break;
    }
    if (kuduPredicate == null) return false;

    kuduConjuncts_.add(predicate);
    kuduPredicates_.add(kuduPredicate);
    return true;
  }

  /**
   * Returns a Kudu comparison operator for the BinaryPredicate operator, or null if
   * the operation is not supported by Kudu.
   */
  private static KuduPredicate.ComparisonOp getKuduOperator(BinaryPredicate.Operator op) {
    switch (op) {
      case GT: return ComparisonOp.GREATER;
      case LT: return ComparisonOp.LESS;
      case GE: return ComparisonOp.GREATER_EQUAL;
      case LE: return ComparisonOp.LESS_EQUAL;
      case EQ: return ComparisonOp.EQUAL;
      default: return null;
    }
  }
}
