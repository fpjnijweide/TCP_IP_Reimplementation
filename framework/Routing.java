package framework;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Routing {
    boolean[] neighbor_available = new boolean[4]; // List of booleans for each node, specifies if we have a direct path to them or not
    long[] neighbor_expiration_time = new long[4]; // Last_seen_time+TTL for each neighbor.
    boolean[][] longTopology; // Network topology stored as a 4x4 adjacency matrix
    boolean[] shortTopology; // Because the adjacency matrix is symmetric and the diagonal is uninteresting
                             // (of course a node can send to itself, we do not need to store that information),
                             // we only need a list of 6 booleans to store the same information as in longTopology
    int highest_assigned_ip = -1;
    int sourceIP = -1;
    List<Integer> postNegotiationSlaveforwardingScheme; // List of IPs that need to forward packets sent by the master node when multicasting
    List<Integer> unicastRouteToMaster; // List of IPs that need to forward packets that we send to the master node
    List<List<Integer>> unicastRoutes = new ArrayList<>();

    List<Integer> getMulticastForwardingRoute(int firstIP) {
        // Use Dijkstra's to find the nodes needed to forward a packet to all nodes (a sort of spanning tree)
        List<Integer> explored = new ArrayList<>();
        List<List<Integer>> exploredPaths = new ArrayList<>();

        List<Integer> frontier = new ArrayList<>();
        List<List<Integer>> frontierPaths = new ArrayList<>();

        for (int i = 0; i <= highest_assigned_ip; i++) {
            if (i != firstIP && longTopology[firstIP][i]) { // If this IP is our neighbor and it is not us
                frontier.add(i); // Add it to the frontier
                frontierPaths.add(new ArrayList<Integer>());
            }
        }

        boolean done = false;
        while (!done) {
            // This while loop is basically Dijkstra's algorithm
            int nodeCost = 99999;
            int node_index = -1;
            for (int i = 0; i < frontier.size(); i++) {
                if (frontierPaths.get(i).size() < nodeCost) {
                    nodeCost = frontierPaths.get(i).size();
                    node_index = i;
                }
            }
            if (node_index == -1) {
                done = true;
                break;
            }
            int node_to_explore = frontier.remove(node_index); // TODO hier gaat het fout
            List<Integer> nodePath = frontierPaths.remove(node_index);


            explored.add(node_to_explore);
            exploredPaths.add(nodePath);

            List<Integer> newNodePath = nodePath.subList(0, nodePath.size());
            newNodePath.add(node_to_explore);


            for (int unknownNode = 0; unknownNode <= highest_assigned_ip; unknownNode++) {
                if (unknownNode != node_to_explore && longTopology[node_to_explore][unknownNode] && !explored.contains(unknownNode)) {
                    // We found a path to a node we haven't explored yet
                    if (!frontier.contains(unknownNode)) {
                        frontier.add(unknownNode);
                        frontierPaths.add(newNodePath);
                    } else {
                        // We found a node that's already in the frontier
                        int unknownNodeFrontierIndex = frontier.indexOf(unknownNode);
                        List<Integer> unknownNodeFrontierPath = frontierPaths.get(unknownNodeFrontierIndex);
                        if (newNodePath.size() < unknownNodeFrontierPath.size()) {
                            // If this path is shorter, change the path of the node in the frontier
                            frontierPaths.set(unknownNodeFrontierIndex, newNodePath);
                        } else if (newNodePath.size() == unknownNodeFrontierPath.size()) {
                            boolean thisPathIsBetter = false;
                            for (int i = 0; i < newNodePath.size(); i++) {
                                if (newNodePath.get(i) > unknownNodeFrontierPath.get(i)) {
                                    break;
                                } else if (newNodePath.get(i) < unknownNodeFrontierPath.get(i)) {
                                    thisPathIsBetter = true;
                                }
                                if (thisPathIsBetter) { // We found a path that covers node with lower IPs, which technically makes it better. Replace the path in the frontier.
                                    frontierPaths.set(unknownNodeFrontierIndex, newNodePath);
                                }
                            }
                        }
                    }
                }
            }
            done = frontier.size() == 0;


        }
        List<Integer> multicastPath = new ArrayList<>();
        for (List<Integer> path : exploredPaths) {
            for (Integer pathNode : path) {
                if (!multicastPath.contains(pathNode)) {
                    multicastPath.add(pathNode); // Add all the shortest paths to the explored nodes to get the multicast path
                }
            }
        }
        return multicastPath;
    }

    public List<Integer> getUnicastForwardingRoute(int firstIP, int secondIP) {
        // Use Dijkstra's to find the shortest route from node A to B. Basically the same as the previous method but without the tree part.
        List<Integer> explored = new ArrayList<>();
        List<List<Integer>> exploredPaths = new ArrayList<>();

        List<Integer> frontier = new ArrayList<>();
        List<List<Integer>> frontierPaths = new ArrayList<>();

        for (int i = 0; i <= highest_assigned_ip; i++) {
            if (i != firstIP && longTopology[firstIP][i]) {
                frontier.add(i);
                frontierPaths.add(new ArrayList<Integer>());
            }
        }

        boolean done = false;
        while (!done) {
            int nodeCost = 99999;
            int node_index = -1;
            for (int i = 0; i < frontier.size(); i++) {
                if (frontierPaths.get(i).size() < nodeCost) {
                    nodeCost = frontierPaths.get(i).size();
                    node_index = i;
                }
            }
            int node_to_explore = frontier.remove(node_index);
            List<Integer> nodePath = frontierPaths.remove(node_index);

            if (node_to_explore == secondIP) {
                done = true;
                return nodePath;
            }

            explored.add(node_to_explore);
            exploredPaths.add(nodePath);

            List<Integer> newNodePath = nodePath.subList(0, nodePath.size());
            newNodePath.add(node_to_explore);


            for (int unknownNode = 0; unknownNode <= highest_assigned_ip; unknownNode++) {
                if (unknownNode != node_to_explore && longTopology[node_to_explore][unknownNode] && !explored.contains(unknownNode)) {
                    // We found a path to a node we haven't explored yet
                    if (!frontier.contains(unknownNode)) {
                        frontier.add(unknownNode);
                        frontierPaths.add(newNodePath);
                    } else {
                        // We found a node that's already in the frontier
                        int unknownNodeFrontierIndex = frontier.indexOf(unknownNode);
                        List<Integer> unknownNodeFrontierPath = frontierPaths.get(unknownNodeFrontierIndex);
                        if (newNodePath.size() < unknownNodeFrontierPath.size()) {
                            frontierPaths.set(unknownNodeFrontierIndex, newNodePath);
                        } else if (newNodePath.size() == unknownNodeFrontierPath.size()) {
                            boolean thisPathIsBetter = false;
                            for (int i = 0; i < newNodePath.size(); i++) {
                                if (newNodePath.get(i) > unknownNodeFrontierPath.get(i)) {
                                    break;
                                } else if (newNodePath.get(i) < unknownNodeFrontierPath.get(i)) {
                                    thisPathIsBetter = true;
                                }
                                if (thisPathIsBetter) {
                                    frontierPaths.set(unknownNodeFrontierIndex, newNodePath);
                                }
                            }
                        }
                    }
                }
            }


        }

        return new ArrayList<>();
    }

    public int getLinkTopologyBits() {
        // Convert the shortened 6-boolean form of the 4x4 adjacency matrix to a 6-bit number
        int resultNumber = 0;
        for (int i = 0; i < shortTopology.length; i++) {
            // start from left
            resultNumber |= (shortTopology[i] ? 1 : 0) << 5 - i;
        }
        return resultNumber;
    }

    public void saveTopology(int receivedTopology) {
        // Convert a 6-bit number that we received to 6 booleans, and store it in shortTopology
        shortTopology = new boolean[6];
        longTopology = new boolean[4][4];
        for (int i = 0; i < shortTopology.length; i++) {
            shortTopology[i] = ((receivedTopology & (1 << (5 - i))) >> (5 - i)) == 1;
        }
        updateLongTopologyFromShortTopology(); // Also store this in the 4x4 adjacency matrix, longTopology
    }

    public int mergeTopologies(List<Integer> topologyNumbers) {
        // Given 0-3 lists of suspected topologies (submitted by slave nodes), the master node calculates the network's topology
        // All we really do here is paste the nth row of the adjacency matrix of each node n into the result matrix
        // (obviously it is the most knowledgeable about its own neighbors, which are found in row n of the matrix)
        // and then make that matrix symmetric
        List<boolean[]> shortTopologies = new ArrayList<>();
        List<boolean[][]> longTopologies = new ArrayList<>();
        boolean[][] resultTopology = new boolean[4][4];

        for (int i = 0; i < topologyNumbers.size(); i++) {
            boolean[] currentShortTopology = new boolean[6];
            boolean[][] currentlongTopology = new boolean[4][4];
            int currentTopologyNumber = topologyNumbers.get(i);
            for (int j = 0; j < currentShortTopology.length; j++) {
                currentShortTopology[j] = ((currentTopologyNumber & (1 << (5 - i))) >> (5 - i)) == 1;
            }
            shortTopologies.add(currentShortTopology);

            for (int k = 0; k <= highest_assigned_ip; k++) {
                for (int l = 0; l <= highest_assigned_ip; l++) {
                    if (k == l) {
                        currentlongTopology[k][l] = true;
                    }
                    if (k == 0 && l > 0) {
                        currentlongTopology[0][l] = currentShortTopology[l - 1];
                    }
                    if (k > 0 && l < k) {
                        currentlongTopology[k][l] = currentlongTopology[l][k];
                    }
                    if (k == 1 && l > 1) {
                        currentlongTopology[1][l] = currentShortTopology[l + 1];
                    }
                    if (k == 2 && l > 2) {
                        currentlongTopology[2][l] = currentShortTopology[l + 2];

                    }
                }
            }
            longTopologies.add(currentlongTopology);

            for (int j = 0; j <= highest_assigned_ip; j++) {
                resultTopology[i][j] = currentlongTopology[i][j];
            }
        }

        // make sure resulttopology is symmetric: if one side is 0, the other side is too
        for (int k = 0; k <= highest_assigned_ip; k++) {
            for (int l = 0; l <= highest_assigned_ip; l++) {
                if (k == l) {
                    resultTopology[k][l] = true;
                }
                if (resultTopology[k][l] != resultTopology[l][k]) {
                    resultTopology[k][l] = true; // TODO under ideal circumstances, both these lines should be setting it to false
                                                 // if two nodes disagree about whether they have a connection, they probably don't have one
                                                 // but because setting it to false here when you don't receive all the request phase packets
                                                 // (which happens due to us losing timing synchronization) leads to index out of bound errors and null pointers later on,
                                                 // we decided to leave it to true. If we were to fix our synchronization issues, this should be set to false.
                    resultTopology[l][k] = true;
                }
            }
        }

        longTopology = resultTopology;
        updateShortTopologyFromLongTopology();
        return getLinkTopologyBits();


    }

    public void checkRoutingTableExpirations() {
        for (int i = 0; i < neighbor_expiration_time.length; i++) {
            if (System.currentTimeMillis() > neighbor_expiration_time[i]) {
                neighbor_expiration_time[i] = 0;
                neighbor_available[i] = false;
            }
        }
        updateTopologyFromAvailableNeighbors();
    }

    public void updateNeighbors(int neighborIP) {
        // Adds an IP to the table of neighbors. Updates shortTopology and longTopology from there. Also sets a timeout, for when the node should be removed.
        checkRoutingTableExpirations();
        neighbor_available[sourceIP] = false; // never list ourselves as neighbor
        neighbor_available[neighborIP] = true;
        int delay = 40 * 1000;
        neighbor_expiration_time[neighborIP] = System.currentTimeMillis() + delay;

        Timer neighbor_expiration_timer = new Timer(delay + 1, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) { // TODO welke delay
                checkRoutingTableExpirations();
            }
        });
        neighbor_expiration_timer.setRepeats(false);
        neighbor_expiration_timer.start();
        updateTopologyFromAvailableNeighbors();
    }

    public void updateLongTopologyFromShortTopology() {
        // because a 4x4 adjacency matrix is symmetric and has diagonal of all 1s (or 0s, depending on your interpretation of whether a node has an edge to itself or not)
        // you can store all its information in only 6 values
        // from those 6 values, stored in ShortTopology, we can update the longTopology (matrix)
        for (int i = 0; i <= highest_assigned_ip; i++) {
            for (int j = 0; j <= highest_assigned_ip; j++) {
                if (i == j) {
                    longTopology[i][j] = true;
                }
                if (i == 0 && j > 0) {
                    longTopology[0][j] = shortTopology[j - 1];
                    longTopology[j][0] = shortTopology[j - 1]; // To make it symmetric
                }
                if (i == 1 && j > 1) {
                    longTopology[1][j] = shortTopology[j + 1];
                    longTopology[j][1] = shortTopology[j + 1];

                }
                if (i == 2 && j > 2) {
                    longTopology[2][j] = shortTopology[j + 2];
                    longTopology[j][2] = shortTopology[j + 2];
                }
            }
        }
    }

    public void updateShortTopologyFromLongTopology() {
        // Other way around
        for (int i = 0; i <= highest_assigned_ip; i++) {
            for (int j = 0; j <= highest_assigned_ip; j++) {
                if (i == 0 && j > 0) {
                    shortTopology[j - 1] = longTopology[0][j];
                }
                if (i == 1 && j > 1) {
                    shortTopology[j + 1] = longTopology[1][j];
                }
                if (i == 2 && j > 2) {
                    shortTopology[j + 2] = longTopology[2][j];

                }
            }
        }
    }

    public void updateTopologyFromAvailableNeighbors() {
        // Update the relevant row in matrix
        for (int i = 0; i <= highest_assigned_ip; i++) {
            if (i != sourceIP) {
                longTopology[sourceIP][i] = neighbor_available[i];
                longTopology[i][sourceIP] = neighbor_available[i];
            }
        }
        // Because it's symmetric: mirror the matrix, and make sure the diagonal is always true
        for (int i = 0; i <= highest_assigned_ip; i++) {
            for (int j = 0; j <= highest_assigned_ip; j++) {
                if (i == j) {
                    longTopology[i][j] = true;
                }
            }
        }
        updateShortTopologyFromLongTopology();
    }


    public int getMulticastForwardingRouteNumber(int ip, List<Integer> route_ips) {
        // If you know your own IP, and have a list of IPs you want to use for some purpose (such as multicast forwarding)
        // in a certain order, you can store this order in a very short format by assigning to each possible permutation a number.
        // We are only interested in permutations of up to size 2.
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        all_ips.remove(ip);

        int first_hop = route_ips.size() > 0 ? route_ips.get(0) : -1; // Get the IP of the first hop. If we do not have a first hop, use -1
        int second_hop = route_ips.size() > 1 ? route_ips.get(1) : -1; // idem dito

        int first_hop_index = all_ips.indexOf(first_hop); // Now, in the list of IPs sorted in ascending order, without your own IP, get the index of these IPs
        int second_hop_index = all_ips.indexOf(second_hop);

        return Mathematics.encodePermutationOfThree(first_hop_index, second_hop_index); // Use these indices to find a number 0-9 which represents this multicast configuration
    }

    public List<Integer> getMulticastForwardingRouteFromOrder(int ip, int order) {
        // Does the reverse of the previous method
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        all_ips.remove(ip);
        return Mathematics.decodePermutationOfThree(order, all_ips);
    }

    public int getUnicastScheme(int sourceIP) {
        // Does the same thing as the methods we just saw, but instead of having to choose from three IPs (all but the source node)
        // we have to choose from two IPs (all but the source and destination).
        // We then do this for every possible destination node, resulting in three numbers from 0-4
        // And store these in a number using base 5.
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        int[] unicast_route_number = new int[3];
        all_ips.remove(sourceIP);
        unicastRoutes.clear();
        for (int i = 0; i < all_ips.size(); i++) {
            int destinationIP = all_ips.get(i);
            if (destinationIP <= highest_assigned_ip) {
                List<Integer> relevant_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
                relevant_ips.remove(sourceIP);
                relevant_ips.remove(destinationIP);

                List<Integer> unicastRoute = getUnicastForwardingRoute(destinationIP, sourceIP);
                unicastRoutes.add(unicastRoute);
                int first_hop = unicastRoute.size() > 0 ? unicastRoute.get(0) : -1;
                int second_hop = unicastRoute.size() > 1 ? unicastRoute.get(1) : -1;

                int first_hop_index = relevant_ips.indexOf(first_hop);
                int second_hop_index = relevant_ips.indexOf(second_hop);

                unicast_route_number[i] = Mathematics.encodePermutationOfTwo(first_hop_index, second_hop_index);
            }
        }
        return unicast_route_number[0] * 5 * 5 + unicast_route_number[1] * 5 + unicast_route_number[2];
    }

}
