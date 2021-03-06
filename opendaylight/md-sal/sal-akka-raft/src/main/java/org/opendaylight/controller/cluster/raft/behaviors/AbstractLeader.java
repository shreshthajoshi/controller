/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.controller.cluster.raft.behaviors;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.Cancellable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.opendaylight.controller.cluster.raft.ClientRequestTracker;
import org.opendaylight.controller.cluster.raft.ClientRequestTrackerImpl;
import org.opendaylight.controller.cluster.raft.FollowerLogInformation;
import org.opendaylight.controller.cluster.raft.FollowerLogInformationImpl;
import org.opendaylight.controller.cluster.raft.RaftActorContext;
import org.opendaylight.controller.cluster.raft.RaftState;
import org.opendaylight.controller.cluster.raft.ReplicatedLogEntry;
import org.opendaylight.controller.cluster.raft.base.messages.CaptureSnapshot;
import org.opendaylight.controller.cluster.raft.base.messages.InitiateInstallSnapshot;
import org.opendaylight.controller.cluster.raft.base.messages.Replicate;
import org.opendaylight.controller.cluster.raft.base.messages.SendHeartBeat;
import org.opendaylight.controller.cluster.raft.base.messages.SendInstallSnapshot;
import org.opendaylight.controller.cluster.raft.messages.AppendEntries;
import org.opendaylight.controller.cluster.raft.messages.AppendEntriesReply;
import org.opendaylight.controller.cluster.raft.messages.InstallSnapshot;
import org.opendaylight.controller.cluster.raft.messages.InstallSnapshotReply;
import org.opendaylight.controller.cluster.raft.messages.RaftRPC;
import org.opendaylight.controller.cluster.raft.messages.RequestVoteReply;
import scala.concurrent.duration.FiniteDuration;

/**
 * The behavior of a RaftActor when it is in the Leader state
 * <p/>
 * Leaders:
 * <ul>
 * <li> Upon election: send initial empty AppendEntries RPCs
 * (heartbeat) to each server; repeat during idle periods to
 * prevent election timeouts (§5.2)
 * <li> If command received from client: append entry to local log,
 * respond after entry applied to state machine (§5.3)
 * <li> If last log index ≥ nextIndex for a follower: send
 * AppendEntries RPC with log entries starting at nextIndex
 * <ul>
 * <li> If successful: update nextIndex and matchIndex for
 * follower (§5.3)
 * <li> If AppendEntries fails because of log inconsistency:
 * decrement nextIndex and retry (§5.3)
 * </ul>
 * <li> If there exists an N such that N > commitIndex, a majority
 * of matchIndex[i] ≥ N, and log[N].term == currentTerm:
 * set commitIndex = N (§5.3, §5.4).
 */
public abstract class AbstractLeader extends AbstractRaftActorBehavior {

    // The index of the first chunk that is sent when installing a snapshot
    public static final int FIRST_CHUNK_INDEX = 1;

    // The index that the follower should respond with if it needs the install snapshot to be reset
    public static final int INVALID_CHUNK_INDEX = -1;

    // This would be passed as the hash code of the last chunk when sending the first chunk
    public static final int INITIAL_LAST_CHUNK_HASH_CODE = -1;

    protected final Map<String, FollowerLogInformation> followerToLog = new HashMap<>();
    protected final Map<String, FollowerToSnapshot> mapFollowerToSnapshot = new HashMap<>();

    protected final Set<String> followers;

    private Cancellable heartbeatSchedule = null;

    private List<ClientRequestTracker> trackerList = new ArrayList<>();

    protected final int minReplicationCount;

    protected final int minIsolatedLeaderPeerCount;

    private Optional<ByteString> snapshot;

    public AbstractLeader(RaftActorContext context) {
        super(context);

        followers = context.getPeerAddresses().keySet();

        for (String followerId : followers) {
            FollowerLogInformation followerLogInformation =
                new FollowerLogInformationImpl(followerId,
                    new AtomicLong(context.getCommitIndex()),
                    new AtomicLong(-1),
                    context.getConfigParams().getElectionTimeOutInterval());

            followerToLog.put(followerId, followerLogInformation);
        }

        leaderId = context.getId();

        if(LOG.isDebugEnabled()) {
            LOG.debug("Election:Leader has following peers: {}", followers);
        }

        minReplicationCount = getMajorityVoteCount(followers.size());

        // the isolated Leader peer count will be 1 less than the majority vote count.
        // this is because the vote count has the self vote counted in it
        // for e.g
        // 0 peers = 1 votesRequired , minIsolatedLeaderPeerCount = 0
        // 2 peers = 2 votesRequired , minIsolatedLeaderPeerCount = 1
        // 4 peers = 3 votesRequired, minIsolatedLeaderPeerCount = 2
        minIsolatedLeaderPeerCount = minReplicationCount > 0 ? (minReplicationCount - 1) : 0;

        snapshot = Optional.absent();

        // Immediately schedule a heartbeat
        // Upon election: send initial empty AppendEntries RPCs
        // (heartbeat) to each server; repeat during idle periods to
        // prevent election timeouts (§5.2)
        scheduleHeartBeat(new FiniteDuration(0, TimeUnit.SECONDS));
    }

    private Optional<ByteString> getSnapshot() {
        return snapshot;
    }

    @VisibleForTesting
    void setSnapshot(Optional<ByteString> snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    protected RaftActorBehavior handleAppendEntries(ActorRef sender,
        AppendEntries appendEntries) {

        if(LOG.isDebugEnabled()) {
            LOG.debug(appendEntries.toString());
        }

        return this;
    }

    @Override
    protected RaftActorBehavior handleAppendEntriesReply(ActorRef sender,
        AppendEntriesReply appendEntriesReply) {

        if(! appendEntriesReply.isSuccess()) {
            if(LOG.isDebugEnabled()) {
                LOG.debug(appendEntriesReply.toString());
            }
        }

        // Update the FollowerLogInformation
        String followerId = appendEntriesReply.getFollowerId();
        FollowerLogInformation followerLogInformation =
            followerToLog.get(followerId);

        if(followerLogInformation == null){
            LOG.error("Unknown follower {}", followerId);
            return this;
        }

        followerLogInformation.markFollowerActive();

        if (appendEntriesReply.isSuccess()) {
            followerLogInformation
                .setMatchIndex(appendEntriesReply.getLogLastIndex());
            followerLogInformation
                .setNextIndex(appendEntriesReply.getLogLastIndex() + 1);
        } else {

            // TODO: When we find that the follower is out of sync with the
            // Leader we simply decrement that followers next index by 1.
            // Would it be possible to do better than this? The RAFT spec
            // does not explicitly deal with it but may be something for us to
            // think about

            followerLogInformation.decrNextIndex();
        }

        // Now figure out if this reply warrants a change in the commitIndex
        // If there exists an N such that N > commitIndex, a majority
        // of matchIndex[i] ≥ N, and log[N].term == currentTerm:
        // set commitIndex = N (§5.3, §5.4).
        for (long N = context.getCommitIndex() + 1; ; N++) {
            int replicatedCount = 1;

            for (FollowerLogInformation info : followerToLog.values()) {
                if (info.getMatchIndex().get() >= N) {
                    replicatedCount++;
                }
            }

            if (replicatedCount >= minReplicationCount) {
                ReplicatedLogEntry replicatedLogEntry = context.getReplicatedLog().get(N);
                if (replicatedLogEntry != null &&
                    replicatedLogEntry.getTerm() == currentTerm()) {
                    context.setCommitIndex(N);
                }
            } else {
                break;
            }
        }

        // Apply the change to the state machine
        if (context.getCommitIndex() > context.getLastApplied()) {
            applyLogToStateMachine(context.getCommitIndex());
        }

        return this;
    }

    protected ClientRequestTracker removeClientRequestTracker(long logIndex) {

        ClientRequestTracker toRemove = findClientRequestTracker(logIndex);
        if(toRemove != null) {
            trackerList.remove(toRemove);
        }

        return toRemove;
    }

    protected ClientRequestTracker findClientRequestTracker(long logIndex) {
        for (ClientRequestTracker tracker : trackerList) {
            if (tracker.getIndex() == logIndex) {
                return tracker;
            }
        }
        return null;
    }

    @Override
    protected RaftActorBehavior handleRequestVoteReply(ActorRef sender,
        RequestVoteReply requestVoteReply) {
        return this;
    }

    @Override
    public RaftState state() {
        return RaftState.Leader;
    }

    @Override
    public RaftActorBehavior handleMessage(ActorRef sender, Object originalMessage) {
        Preconditions.checkNotNull(sender, "sender should not be null");

        Object message = fromSerializableMessage(originalMessage);

        if (message instanceof RaftRPC) {
            RaftRPC rpc = (RaftRPC) message;
            // If RPC request or response contains term T > currentTerm:
            // set currentTerm = T, convert to follower (§5.1)
            // This applies to all RPC messages and responses
            if (rpc.getTerm() > context.getTermInformation().getCurrentTerm()) {
                context.getTermInformation().updateAndPersist(rpc.getTerm(), null);

                return switchBehavior(new Follower(context));
            }
        }

        try {
            if (message instanceof SendHeartBeat) {
                sendHeartBeat();
                return this;

            } else if(message instanceof InitiateInstallSnapshot) {
                installSnapshotIfNeeded();

            } else if(message instanceof SendInstallSnapshot) {
                // received from RaftActor
                setSnapshot(Optional.of(((SendInstallSnapshot) message).getSnapshot()));
                sendInstallSnapshot();

            } else if (message instanceof Replicate) {
                replicate((Replicate) message);

            } else if (message instanceof InstallSnapshotReply){
                handleInstallSnapshotReply((InstallSnapshotReply) message);

            }
        } finally {
            scheduleHeartBeat(context.getConfigParams().getHeartBeatInterval());
        }

        return super.handleMessage(sender, message);
    }

    private void handleInstallSnapshotReply(InstallSnapshotReply reply) {
        String followerId = reply.getFollowerId();
        FollowerToSnapshot followerToSnapshot = mapFollowerToSnapshot.get(followerId);
        FollowerLogInformation followerLogInformation = followerToLog.get(followerId);
        followerLogInformation.markFollowerActive();

        if (followerToSnapshot != null &&
            followerToSnapshot.getChunkIndex() == reply.getChunkIndex()) {

            if (reply.isSuccess()) {
                if(followerToSnapshot.isLastChunk(reply.getChunkIndex())) {
                    //this was the last chunk reply
                    if(LOG.isDebugEnabled()) {
                        LOG.debug("InstallSnapshotReply received, " +
                                "last chunk received, Chunk:{}. Follower:{} Setting nextIndex:{}",
                            reply.getChunkIndex(), followerId,
                            context.getReplicatedLog().getSnapshotIndex() + 1
                        );
                    }

                    followerLogInformation.setMatchIndex(
                        context.getReplicatedLog().getSnapshotIndex());
                    followerLogInformation.setNextIndex(
                        context.getReplicatedLog().getSnapshotIndex() + 1);
                    mapFollowerToSnapshot.remove(followerId);

                    if(LOG.isDebugEnabled()) {
                        LOG.debug("followerToLog.get(followerId).getNextIndex().get()=" +
                            followerToLog.get(followerId).getNextIndex().get());
                    }

                    if (mapFollowerToSnapshot.isEmpty()) {
                        // once there are no pending followers receiving snapshots
                        // we can remove snapshot from the memory
                        setSnapshot(Optional.<ByteString>absent());
                    }

                } else {
                    followerToSnapshot.markSendStatus(true);
                }
            } else {
                LOG.info("InstallSnapshotReply received, " +
                        "sending snapshot chunk failed, Will retry, Chunk:{}",
                    reply.getChunkIndex()
                );

                followerToSnapshot.markSendStatus(false);
            }

        } else {
            LOG.error("ERROR!!" +
                    "FollowerId in InstallSnapshotReply not known to Leader" +
                    " or Chunk Index in InstallSnapshotReply not matching {} != {}",
                followerToSnapshot.getChunkIndex(), reply.getChunkIndex()
            );

            if(reply.getChunkIndex() == INVALID_CHUNK_INDEX){
                // Since the Follower did not find this index to be valid we should reset the follower snapshot
                // so that Installing the snapshot can resume from the beginning
                followerToSnapshot.reset();
            }
        }
    }

    private void replicate(Replicate replicate) {
        long logIndex = replicate.getReplicatedLogEntry().getIndex();

        if(LOG.isDebugEnabled()) {
            LOG.debug("Replicate message {}", logIndex);
        }

        // Create a tracker entry we will use this later to notify the
        // client actor
        trackerList.add(
            new ClientRequestTrackerImpl(replicate.getClientActor(),
                replicate.getIdentifier(),
                logIndex)
        );

        if (followers.size() == 0) {
            context.setCommitIndex(logIndex);
            applyLogToStateMachine(logIndex);
        } else {
            sendAppendEntries();
        }
    }

    private void sendAppendEntries() {
        // Send an AppendEntries to all followers
        for (String followerId : followers) {
            ActorSelection followerActor = context.getPeerActorSelection(followerId);

            if (followerActor != null) {
                FollowerLogInformation followerLogInformation = followerToLog.get(followerId);
                long followerNextIndex = followerLogInformation.getNextIndex().get();
                boolean isFollowerActive = followerLogInformation.isFollowerActive();
                List<ReplicatedLogEntry> entries = null;

                if (mapFollowerToSnapshot.get(followerId) != null) {
                    // if install snapshot is in process , then sent next chunk if possible
                    if (isFollowerActive && mapFollowerToSnapshot.get(followerId).canSendNextChunk()) {
                        sendSnapshotChunk(followerActor, followerId);
                    } else {
                        // we send a heartbeat even if we have not received a reply for the last chunk
                        sendAppendEntriesToFollower(followerActor, followerNextIndex,
                            Collections.<ReplicatedLogEntry>emptyList());
                    }

                } else {
                    long leaderLastIndex = context.getReplicatedLog().lastIndex();
                    long leaderSnapShotIndex = context.getReplicatedLog().getSnapshotIndex();

                    if (isFollowerActive &&
                        context.getReplicatedLog().isPresent(followerNextIndex)) {
                        // FIXME : Sending one entry at a time
                        entries = context.getReplicatedLog().getFrom(followerNextIndex, 1);

                    } else if (isFollowerActive && followerNextIndex >= 0 &&
                        leaderLastIndex >= followerNextIndex ) {
                        // if the followers next index is not present in the leaders log, and
                        // if the follower is just not starting and if leader's index is more than followers index
                        // then snapshot should be sent

                        if(LOG.isDebugEnabled()) {
                            LOG.debug("InitiateInstallSnapshot to follower:{}," +
                                    "follower-nextIndex:{}, leader-snapshot-index:{},  " +
                                    "leader-last-index:{}", followerId,
                                followerNextIndex, leaderSnapShotIndex, leaderLastIndex
                            );
                        }
                        actor().tell(new InitiateInstallSnapshot(), actor());

                        // we would want to sent AE as the capture snapshot might take time
                        entries =  Collections.<ReplicatedLogEntry>emptyList();

                    } else {
                        //we send an AppendEntries, even if the follower is inactive
                        // in-order to update the followers timestamp, in case it becomes active again
                        entries =  Collections.<ReplicatedLogEntry>emptyList();
                    }

                    sendAppendEntriesToFollower(followerActor, followerNextIndex, entries);

                }
            }
        }
    }

    private void sendAppendEntriesToFollower(ActorSelection followerActor, long followerNextIndex,
        List<ReplicatedLogEntry> entries) {
        followerActor.tell(
            new AppendEntries(currentTerm(), context.getId(),
                prevLogIndex(followerNextIndex),
                prevLogTerm(followerNextIndex), entries,
                context.getCommitIndex()).toSerializable(),
            actor()
        );
    }

    /**
     * An installSnapshot is scheduled at a interval that is a multiple of
     * a HEARTBEAT_INTERVAL. This is to avoid the need to check for installing
     * snapshots at every heartbeat.
     *
     * Install Snapshot works as follows
     * 1. Leader sends a InitiateInstallSnapshot message to self
     * 2. Leader then initiates the capture snapshot by sending a CaptureSnapshot message to actor
     * 3. RaftActor on receipt of the CaptureSnapshotReply (from Shard), stores the received snapshot in the replicated log
     * and makes a call to Leader's handleMessage , with SendInstallSnapshot message.
     * 4. Leader , picks the snapshot from im-mem ReplicatedLog and sends it in chunks to the Follower
     * 5. On complete, Follower sends back a InstallSnapshotReply.
     * 6. On receipt of the InstallSnapshotReply for the last chunk, Leader marks the install complete for that follower
     * and replenishes the memory by deleting the snapshot in Replicated log.
     *
     */
    private void installSnapshotIfNeeded() {
        for (String followerId : followers) {
            ActorSelection followerActor =
                context.getPeerActorSelection(followerId);

            if(followerActor != null) {
                FollowerLogInformation followerLogInformation =
                    followerToLog.get(followerId);

                long nextIndex = followerLogInformation.getNextIndex().get();

                if (!context.getReplicatedLog().isPresent(nextIndex) &&
                    context.getReplicatedLog().isInSnapshot(nextIndex)) {
                    LOG.info("{} follower needs a snapshot install", followerId);
                    if (snapshot.isPresent()) {
                        // if a snapshot is present in the memory, most likely another install is in progress
                        // no need to capture snapshot
                        sendSnapshotChunk(followerActor, followerId);

                    } else {
                        initiateCaptureSnapshot();
                        //we just need 1 follower who would need snapshot to be installed.
                        // when we have the snapshot captured, we would again check (in SendInstallSnapshot)
                        // who needs an install and send to all who need
                        break;
                    }

                }
            }
        }
    }

    // on every install snapshot, we try to capture the snapshot.
    // Once a capture is going on, another one issued will get ignored by RaftActor.
    private void initiateCaptureSnapshot() {
        LOG.info("Initiating Snapshot Capture to Install Snapshot, Leader:{}", getLeaderId());
        ReplicatedLogEntry lastAppliedEntry = context.getReplicatedLog().get(context.getLastApplied());
        long lastAppliedIndex = -1;
        long lastAppliedTerm = -1;

        if (lastAppliedEntry != null) {
            lastAppliedIndex = lastAppliedEntry.getIndex();
            lastAppliedTerm = lastAppliedEntry.getTerm();
        } else if (context.getReplicatedLog().getSnapshotIndex() > -1)  {
            lastAppliedIndex = context.getReplicatedLog().getSnapshotIndex();
            lastAppliedTerm = context.getReplicatedLog().getSnapshotTerm();
        }

        boolean isInstallSnapshotInitiated = true;
        actor().tell(new CaptureSnapshot(lastIndex(), lastTerm(),
                lastAppliedIndex, lastAppliedTerm, isInstallSnapshotInitiated),
            actor());
    }


    private void sendInstallSnapshot() {
        for (String followerId : followers) {
            ActorSelection followerActor = context.getPeerActorSelection(followerId);

            if(followerActor != null) {
                FollowerLogInformation followerLogInformation = followerToLog.get(followerId);
                long nextIndex = followerLogInformation.getNextIndex().get();

                if (!context.getReplicatedLog().isPresent(nextIndex) &&
                    context.getReplicatedLog().isInSnapshot(nextIndex)) {
                    sendSnapshotChunk(followerActor, followerId);
                }
            }
        }
    }

    /**
     *  Sends a snapshot chunk to a given follower
     *  InstallSnapshot should qualify as a heartbeat too.
     */
    private void sendSnapshotChunk(ActorSelection followerActor, String followerId) {
        try {
            if (snapshot.isPresent()) {
                followerActor.tell(
                    new InstallSnapshot(currentTerm(), context.getId(),
                        context.getReplicatedLog().getSnapshotIndex(),
                        context.getReplicatedLog().getSnapshotTerm(),
                        getNextSnapshotChunk(followerId,snapshot.get()),
                        mapFollowerToSnapshot.get(followerId).incrementChunkIndex(),
                        mapFollowerToSnapshot.get(followerId).getTotalChunks(),
                        Optional.of(mapFollowerToSnapshot.get(followerId).getLastChunkHashCode())
                    ).toSerializable(),
                    actor()
                );
                LOG.info("InstallSnapshot sent to follower {}, Chunk: {}/{}",
                    followerActor.path(), mapFollowerToSnapshot.get(followerId).getChunkIndex(),
                    mapFollowerToSnapshot.get(followerId).getTotalChunks());
            }
        } catch (IOException e) {
            LOG.error(e, "InstallSnapshot failed for Leader.");
        }
    }

    /**
     * Acccepts snaphot as ByteString, enters into map for future chunks
     * creates and return a ByteString chunk
     */
    private ByteString getNextSnapshotChunk(String followerId, ByteString snapshotBytes) throws IOException {
        FollowerToSnapshot followerToSnapshot = mapFollowerToSnapshot.get(followerId);
        if (followerToSnapshot == null) {
            followerToSnapshot = new FollowerToSnapshot(snapshotBytes);
            mapFollowerToSnapshot.put(followerId, followerToSnapshot);
        }
        ByteString nextChunk = followerToSnapshot.getNextChunk();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Leader's snapshot nextChunk size:{}", nextChunk.size());
        }
        return nextChunk;
    }

    private void sendHeartBeat() {
        if (followers.size() > 0) {
            sendAppendEntries();
        }
    }

    private void stopHeartBeat() {
        if (heartbeatSchedule != null && !heartbeatSchedule.isCancelled()) {
            heartbeatSchedule.cancel();
        }
    }

    private void scheduleHeartBeat(FiniteDuration interval) {
        if(followers.size() == 0){
            // Optimization - do not bother scheduling a heartbeat as there are
            // no followers
            return;
        }

        stopHeartBeat();

        // Schedule a heartbeat. When the scheduler triggers a SendHeartbeat
        // message is sent to itself.
        // Scheduling the heartbeat only once here because heartbeats do not
        // need to be sent if there are other messages being sent to the remote
        // actor.
        heartbeatSchedule = context.getActorSystem().scheduler().scheduleOnce(
            interval, context.getActor(), new SendHeartBeat(),
            context.getActorSystem().dispatcher(), context.getActor());
    }

    @Override
    public void close() throws Exception {
        stopHeartBeat();
    }

    @Override
    public String getLeaderId() {
        return context.getId();
    }

    protected boolean isLeaderIsolated() {
        int minPresent = minIsolatedLeaderPeerCount;
        for (FollowerLogInformation followerLogInformation : followerToLog.values()) {
            if (followerLogInformation.isFollowerActive()) {
                --minPresent;
                if (minPresent == 0) {
                    break;
                }
            }
        }
        return (minPresent != 0);
    }

    /**
     * Encapsulates the snapshot bytestring and handles the logic of sending
     * snapshot chunks
     */
    protected class FollowerToSnapshot {
        private ByteString snapshotBytes;
        private int offset = 0;
        // the next snapshot chunk is sent only if the replyReceivedForOffset matches offset
        private int replyReceivedForOffset;
        // if replyStatus is false, the previous chunk is attempted
        private boolean replyStatus = false;
        private int chunkIndex;
        private int totalChunks;
        private int lastChunkHashCode = AbstractLeader.INITIAL_LAST_CHUNK_HASH_CODE;
        private int nextChunkHashCode = AbstractLeader.INITIAL_LAST_CHUNK_HASH_CODE;

        public FollowerToSnapshot(ByteString snapshotBytes) {
            this.snapshotBytes = snapshotBytes;
            int size = snapshotBytes.size();
            totalChunks = ( size / context.getConfigParams().getSnapshotChunkSize()) +
                ((size % context.getConfigParams().getSnapshotChunkSize()) > 0 ? 1 : 0);
            if(LOG.isDebugEnabled()) {
                LOG.debug("Snapshot {} bytes, total chunks to send:{}",
                    size, totalChunks);
            }
            replyReceivedForOffset = -1;
            chunkIndex = AbstractLeader.FIRST_CHUNK_INDEX;
        }

        public ByteString getSnapshotBytes() {
            return snapshotBytes;
        }

        public int incrementOffset() {
            if(replyStatus) {
                // if prev chunk failed, we would want to sent the same chunk again
                offset = offset + context.getConfigParams().getSnapshotChunkSize();
            }
            return offset;
        }

        public int incrementChunkIndex() {
            if (replyStatus) {
                // if prev chunk failed, we would want to sent the same chunk again
                chunkIndex =  chunkIndex + 1;
            }
            return chunkIndex;
        }

        public int getChunkIndex() {
            return chunkIndex;
        }

        public int getTotalChunks() {
            return totalChunks;
        }

        public boolean canSendNextChunk() {
            // we only send a false if a chunk is sent but we have not received a reply yet
            return replyReceivedForOffset == offset;
        }

        public boolean isLastChunk(int chunkIndex) {
            return totalChunks == chunkIndex;
        }

        public void markSendStatus(boolean success) {
            if (success) {
                // if the chunk sent was successful
                replyReceivedForOffset = offset;
                replyStatus = true;
                lastChunkHashCode = nextChunkHashCode;
            } else {
                // if the chunk sent was failure
                replyReceivedForOffset = offset;
                replyStatus = false;
            }
        }

        public ByteString getNextChunk() {
            int snapshotLength = getSnapshotBytes().size();
            int start = incrementOffset();
            int size = context.getConfigParams().getSnapshotChunkSize();
            if (context.getConfigParams().getSnapshotChunkSize() > snapshotLength) {
                size = snapshotLength;
            } else {
                if ((start + context.getConfigParams().getSnapshotChunkSize()) > snapshotLength) {
                    size = snapshotLength - start;
                }
            }

            if(LOG.isDebugEnabled()) {
                LOG.debug("length={}, offset={},size={}",
                    snapshotLength, start, size);
            }
            ByteString substring = getSnapshotBytes().substring(start, start + size);
            nextChunkHashCode = substring.hashCode();
            return substring;
        }

        /**
         * reset should be called when the Follower needs to be sent the snapshot from the beginning
         */
        public void reset(){
            offset = 0;
            replyStatus = false;
            replyReceivedForOffset = offset;
            chunkIndex = AbstractLeader.FIRST_CHUNK_INDEX;
            lastChunkHashCode = AbstractLeader.INITIAL_LAST_CHUNK_HASH_CODE;
        }

        public int getLastChunkHashCode() {
            return lastChunkHashCode;
        }
    }

    // called from example-actor for printing the follower-states
    public String printFollowerStates() {
        StringBuilder sb = new StringBuilder();
        for(FollowerLogInformation followerLogInformation : followerToLog.values()) {
            boolean isFollowerActive = followerLogInformation.isFollowerActive();
            sb.append("{"+followerLogInformation.getId() + " state:" + isFollowerActive + "},");

        }
        return "[" + sb.toString() + "]";
    }

    @VisibleForTesting
    void markFollowerActive(String followerId) {
        followerToLog.get(followerId).markFollowerActive();
    }
}
