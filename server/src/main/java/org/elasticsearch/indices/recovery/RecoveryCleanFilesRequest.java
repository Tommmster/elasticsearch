/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.indices.recovery;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;

import java.io.IOException;

public class RecoveryCleanFilesRequest extends RecoveryTransportRequest {

    private final long recoveryId;
    private final ShardId shardId;
    private final Store.MetadataSnapshot snapshotFiles;
    private final int totalTranslogOps;
    private final long globalCheckpoint;

    public RecoveryCleanFilesRequest(long recoveryId, long requestSeqNo, ShardId shardId, Store.MetadataSnapshot snapshotFiles,
                              int totalTranslogOps, long globalCheckpoint) {
        super(requestSeqNo);
        this.recoveryId = recoveryId;
        this.shardId = shardId;
        this.snapshotFiles = snapshotFiles;
        this.totalTranslogOps = totalTranslogOps;
        this.globalCheckpoint = globalCheckpoint;
    }

    RecoveryCleanFilesRequest(StreamInput in) throws IOException {
        super(in);
        recoveryId = in.readLong();
        shardId = new ShardId(in);
        snapshotFiles = new Store.MetadataSnapshot(in);
        totalTranslogOps = in.readVInt();
        globalCheckpoint = in.readZLong();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeLong(recoveryId);
        shardId.writeTo(out);
        snapshotFiles.writeTo(out);
        out.writeVInt(totalTranslogOps);
        out.writeZLong(globalCheckpoint);
    }

    public Store.MetadataSnapshot sourceMetaSnapshot() {
        return snapshotFiles;
    }

    public long recoveryId() {
        return this.recoveryId;
    }

    public ShardId shardId() {
        return shardId;
    }

    public int totalTranslogOps() {
        return totalTranslogOps;
    }

    public long getGlobalCheckpoint() {
        return globalCheckpoint;
    }
}
