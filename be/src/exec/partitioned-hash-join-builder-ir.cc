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

#include "exec/partitioned-hash-join-builder.h"

#include "codegen/impala-ir.h"
#include "exec/hash-table.inline.h"
#include "runtime/buffered-tuple-stream.inline.h"
#include "runtime/raw-value.inline.h"
#include "runtime/row-batch.h"
#include "runtime/runtime-filter.h"
#include "util/bloom-filter.h"

#include "common/names.h"

using namespace impala;

inline Status PhjBuilder::AppendRow(BufferedTupleStream* stream, TupleRow* row) {
  Status status;
  if (LIKELY(stream->AddRow(row, &status))) return Status::OK();
  RETURN_IF_ERROR(status);
  return AppendRowStreamFull(stream, row);
}

Status PhjBuilder::ProcessBuildBatch(
    RowBatch* build_batch, HashTableCtx* ctx, bool build_filters) {
  HashTableCtx::ExprValuesCache* expr_vals_cache = ctx->expr_values_cache();
  expr_vals_cache->Reset();
  FOREACH_ROW(build_batch, 0, build_batch_iter) {
    TupleRow* build_row = build_batch_iter.Get();
    if (!ctx->EvalAndHashBuild(build_row)) {
      if (null_aware_partition_ != NULL) {
        // TODO: remove with codegen/template
        // If we are NULL aware and this build row has NULL in the eq join slot,
        // append it to the null_aware partition. We will need it later.
        RETURN_IF_ERROR(AppendRow(null_aware_partition_->build_rows(), build_row));
      }
      continue;
    }
    if (build_filters) {
      DCHECK_EQ(ctx->level(), 0)
          << "Runtime filters should not be built during repartitioning.";
      for (const FilterContext& ctx : filters_) {
        // TODO: codegen expr evaluation and hashing
        if (ctx.local_bloom_filter == NULL) continue;
        void* e = ctx.expr->GetValue(build_row);
        uint32_t filter_hash = RawValue::GetHashValue(
            e, ctx.expr->root()->type(), RuntimeFilterBank::DefaultHashSeed());
        ctx.local_bloom_filter->Insert(filter_hash);
      }
    }
    const uint32_t hash = expr_vals_cache->CurExprValuesHash();
    const uint32_t partition_idx = hash >> (32 - NUM_PARTITIONING_BITS);
    Partition* partition = hash_partitions_[partition_idx];
    RETURN_IF_ERROR(AppendRow(partition->build_rows(), build_row));
  }
  return Status::OK();
}

bool PhjBuilder::Partition::InsertBatch(TPrefetchMode::type prefetch_mode,
    HashTableCtx* ht_ctx, RowBatch* batch,
    const vector<BufferedTupleStream::RowIdx>& indices) {
  // Compute the hash values and prefetch the hash table buckets.
  const int num_rows = batch->num_rows();
  HashTableCtx::ExprValuesCache* expr_vals_cache = ht_ctx->expr_values_cache();
  const int prefetch_size = expr_vals_cache->capacity();
  const BufferedTupleStream::RowIdx* row_indices = indices.data();
  for (int prefetch_group_row = 0; prefetch_group_row < num_rows;
       prefetch_group_row += prefetch_size) {
    int cur_row = prefetch_group_row;
    expr_vals_cache->Reset();
    FOREACH_ROW_LIMIT(batch, cur_row, prefetch_size, batch_iter) {
      if (ht_ctx->EvalAndHashBuild(batch_iter.Get())) {
        if (prefetch_mode != TPrefetchMode::NONE) {
          hash_tbl_->PrefetchBucket<false>(expr_vals_cache->CurExprValuesHash());
        }
      } else {
        expr_vals_cache->SetRowNull();
      }
      expr_vals_cache->NextRow();
    }
    // Do the insertion.
    expr_vals_cache->ResetForRead();
    FOREACH_ROW_LIMIT(batch, cur_row, prefetch_size, batch_iter) {
      TupleRow* row = batch_iter.Get();
      BufferedTupleStream::RowIdx row_idx = row_indices[cur_row];
      if (!expr_vals_cache->IsRowNull()
          && UNLIKELY(!hash_tbl_->Insert(ht_ctx, row_idx, row))) {
        return false;
      }
      expr_vals_cache->NextRow();
      ++cur_row;
    }
  }
  return true;
}
