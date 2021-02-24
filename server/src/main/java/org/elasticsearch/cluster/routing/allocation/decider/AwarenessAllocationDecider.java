/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation.decider;

import com.carrotsearch.hppc.ObjectIntHashMap;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.StreamSupport;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.elasticsearch.cluster.metadata.IndexMetadata.INDEX_AUTO_EXPAND_REPLICAS_SETTING;

/**
 * This {@link AllocationDecider} controls shard allocation based on
 * {@code awareness} key-value pairs defined in the node configuration.
 * Awareness explicitly controls where replicas should be allocated based on
 * attributes like node or physical rack locations. Awareness attributes accept
 * arbitrary configuration keys like a rack data-center identifier. For example
 * the setting:
 * <pre>
 * cluster.routing.allocation.awareness.attributes: rack_id
 * </pre>
 * <p>
 * will cause allocations to be distributed over different racks such that
 * ideally at least one replicas of the all shard is available on the same rack.
 * To enable allocation awareness in this example nodes should contain a value
 * for the {@code rack_id} key like:
 * <pre>
 * node.attr.rack_id:1
 * </pre>
 * <p>
 * Awareness can also be used to prevent over-allocation in the case of node or
 * even "zone" failure. For example in cloud-computing infrastructures like
 * Amazon AWS a cluster might span over multiple "zones". Awareness can be used
 * to distribute replicas to individual zones by setting:
 * <pre>
 * cluster.routing.allocation.awareness.attributes: zone
 * </pre>
 * <p>
 * and forcing allocation to be aware of the following zone the data resides in:
 * <pre>
 * cluster.routing.allocation.awareness.force.zone.values: zone1,zone2
 * </pre>
 * <p>
 * In contrast to regular awareness this setting will prevent over-allocation on
 * {@code zone1} even if {@code zone2} fails partially or becomes entirely
 * unavailable. Nodes that belong to a certain zone / group should be started
 * with the zone id configured on the node-level settings like:
 * <pre>
 * node.zone: zone1
 * </pre>
 */
public class AwarenessAllocationDecider extends AllocationDecider {

    public static final String NAME = "awareness";

    public static final Setting<List<String>> CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING =
        Setting.listSetting("cluster.routing.allocation.awareness.attributes", emptyList(), Function.identity(), Property.Dynamic,
            Property.NodeScope);
    public static final Setting<Settings> CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCE_GROUP_SETTING =
        Setting.groupSetting("cluster.routing.allocation.awareness.force.", Property.Dynamic, Property.NodeScope);

    private volatile List<String> awarenessAttributes;

    private volatile Map<String, List<String>> forcedAwarenessAttributes;

    public AwarenessAllocationDecider(Settings settings, ClusterSettings clusterSettings) {
        this.awarenessAttributes = CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING.get(settings);
        clusterSettings.addSettingsUpdateConsumer(CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING, this::setAwarenessAttributes);
        setForcedAwarenessAttributes(CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCE_GROUP_SETTING.get(settings));
        clusterSettings.addSettingsUpdateConsumer(CLUSTER_ROUTING_ALLOCATION_AWARENESS_FORCE_GROUP_SETTING,
                this::setForcedAwarenessAttributes);
    }

    private void setForcedAwarenessAttributes(Settings forceSettings) {
        Map<String, List<String>> forcedAwarenessAttributes = new HashMap<>();
        Map<String, Settings> forceGroups = forceSettings.getAsGroups();
        for (Map.Entry<String, Settings> entry : forceGroups.entrySet()) {
            List<String> aValues = entry.getValue().getAsList("values");
            if (aValues.size() > 0) {
                forcedAwarenessAttributes.put(entry.getKey(), aValues);
            }
        }
        this.forcedAwarenessAttributes = forcedAwarenessAttributes;
    }

    private void setAwarenessAttributes(List<String> awarenessAttributes) {
        this.awarenessAttributes = awarenessAttributes;
    }

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return underCapacity(shardRouting, node, allocation, true);
    }

    @Override
    public Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return underCapacity(shardRouting, node, allocation, false);
    }

    private static final Decision YES_NOT_ENABLED = Decision.single(Decision.Type.YES, NAME,
            "allocation awareness is not enabled, set cluster setting ["
                    + CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING.getKey() + "] to enable it");

    private static final Decision YES_AUTO_EXPAND_ALL = Decision.single(Decision.Type.YES, NAME,
            "allocation awareness is ignored, this index is set to auto-expand to all nodes");

    private static final Decision YES_ALL_MET =
            Decision.single(Decision.Type.YES, NAME, "node meets all awareness attribute requirements");

    private Decision underCapacity(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation, boolean moveToNode) {
        if (awarenessAttributes.isEmpty()) {
            return YES_NOT_ENABLED;
        }

        final boolean debug = allocation.debugDecision();
        IndexMetadata indexMetadata = allocation.metadata().getIndexSafe(shardRouting.index());

        if (INDEX_AUTO_EXPAND_REPLICAS_SETTING.get(indexMetadata.getSettings()).expandToAllNodes()) {
            return YES_AUTO_EXPAND_ALL;
        }

        int shardCount = indexMetadata.getNumberOfReplicas() + 1; // 1 for primary
        for (String awarenessAttribute : awarenessAttributes) {
            // the node the shard exists on must be associated with an awareness attribute
            if (node.node().getAttributes().containsKey(awarenessAttribute) == false) {
                return debug ? debugNoMissingAttribute(awarenessAttribute, awarenessAttributes) : Decision.NO;
            }

            // build attr_value -> nodes map
            ObjectIntHashMap<String> nodesPerAttribute = allocation.routingNodes().nodesPerAttributesCounts(awarenessAttribute);

            // build the count of shards per attribute value
            ObjectIntHashMap<String> shardPerAttribute = new ObjectIntHashMap<>();
            for (ShardRouting assignedShard : allocation.routingNodes().assignedShards(shardRouting.shardId())) {
                if (assignedShard.started() || assignedShard.initializing()) {
                    // Note: this also counts relocation targets as that will be the new location of the shard.
                    // Relocation sources should not be counted as the shard is moving away
                    RoutingNode routingNode = allocation.routingNodes().node(assignedShard.currentNodeId());
                    shardPerAttribute.addTo(routingNode.node().getAttributes().get(awarenessAttribute), 1);
                }
            }

            if (moveToNode) {
                if (shardRouting.assignedToNode()) {
                    String nodeId = shardRouting.relocating() ? shardRouting.relocatingNodeId() : shardRouting.currentNodeId();
                    if (node.nodeId().equals(nodeId) == false) {
                        // we work on different nodes, move counts around
                        shardPerAttribute.putOrAdd(allocation.routingNodes().node(nodeId).node().getAttributes().get(awarenessAttribute),
                                0, -1);
                        shardPerAttribute.addTo(node.node().getAttributes().get(awarenessAttribute), 1);
                    }
                } else {
                    shardPerAttribute.addTo(node.node().getAttributes().get(awarenessAttribute), 1);
                }
            }

            int numberOfAttributes = nodesPerAttribute.size();
            List<String> fullValues = forcedAwarenessAttributes.get(awarenessAttribute);
            if (fullValues != null) {
                for (String fullValue : fullValues) {
                    if (shardPerAttribute.containsKey(fullValue) == false) {
                        numberOfAttributes++;
                    }
                }
            }
            // TODO should we remove ones that are not part of full list?

            final int currentNodeCount = shardPerAttribute.get(node.node().getAttributes().get(awarenessAttribute));
            final int maximumNodeCount = (shardCount + numberOfAttributes - 1) / numberOfAttributes; // ceil(shardCount/numberOfAttributes)
            if (currentNodeCount > maximumNodeCount) {
                return debug ? debugNoTooManyCopies(
                        shardCount,
                        awarenessAttribute,
                        node.node().getAttributes().get(awarenessAttribute),
                        numberOfAttributes,
                        StreamSupport.stream(nodesPerAttribute.keys().spliterator(), false).map(c -> c.value).sorted().collect(toList()),
                        fullValues == null ? null : fullValues.stream().sorted().collect(toList()),
                        currentNodeCount,
                        maximumNodeCount)
                        : Decision.NO;
            }
        }

        return YES_ALL_MET;
    }

    private static Decision debugNoTooManyCopies(
            int shardCount,
            String attributeName,
            String attributeValue,
            int numberOfAttributes,
            List<String> realAttributes,
            List<String> forcedAttributes,
            int currentNodeCount,
            int maximumNodeCount) {
        return Decision.single(Decision.Type.NO, NAME,
                "there are [%d] copies of this shard and [%d] values for attribute [%s] (%s from nodes in the cluster and %s) so there " +
                        "may be at most [%d] copies of this shard allocated to nodes with each value, but (including this copy) there " +
                        "would be [%d] copies allocated to nodes with [node.attr.%s: %s]",
                shardCount,
                numberOfAttributes,
                attributeName,
                realAttributes,
                forcedAttributes == null ? "no forced awareness" : forcedAttributes + " from forced awareness",
                maximumNodeCount,
                currentNodeCount,
                attributeName,
                attributeValue);
    }

    private static Decision debugNoMissingAttribute(String awarenessAttribute, List<String> awarenessAttributes) {
        return Decision.single(Decision.Type.NO, NAME,
                "node does not contain the awareness attribute [%s]; required attributes cluster setting [%s=%s]", awarenessAttribute,
                CLUSTER_ROUTING_ALLOCATION_AWARENESS_ATTRIBUTE_SETTING.getKey(),
                Strings.collectionToCommaDelimitedString(awarenessAttributes));
    }
}
