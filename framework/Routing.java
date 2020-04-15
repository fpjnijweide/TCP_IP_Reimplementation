package framework;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Routing {
    boolean[] neighbor_available = new boolean[4];
    long[] neighbor_expiration_time = new long[4];
    boolean[] shortTopology;
    boolean[][] longTopology;
    int highest_assigned_ip = -1;
    int sourceIP = -1;
    List<Integer> postNegotiationSlaveforwardingScheme;
    List<Integer> unicastRouteToMaster;
    List<List<Integer>> unicastRoutes = new ArrayList<>();

    List<Integer> getMulticastForwardingRoute(int firstIP) {
        List<Integer> explored = new ArrayList<>();
        List<List<Integer>> exploredPaths = new ArrayList<>();

        List<Integer> frontier = new ArrayList<>();
        List<List<Integer>> frontierPaths = new ArrayList<>();

        for (int i = 0; i <= highest_assigned_ip; i++) {
            if (i!=firstIP && longTopology[firstIP][i]){
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
            if (node_index==-1){
                done=true;
                break;
            }
            int node_to_explore = frontier.remove(node_index); // TODO hier gaat het fout
            List<Integer> nodePath = frontierPaths.remove(node_index);


            explored.add(node_to_explore);
            exploredPaths.add(nodePath);

            List<Integer> newNodePath = nodePath.subList(0,nodePath.size());
            newNodePath.add(node_to_explore);


            for (int unknownNode = 0; unknownNode <= highest_assigned_ip; unknownNode++) {
                if (unknownNode!=node_to_explore && longTopology[node_to_explore][unknownNode] && !explored.contains(unknownNode)){
                    // We found a path to a node we haven't explored yet
                    if (!frontier.contains(unknownNode)) {
                        frontier.add(unknownNode);
                        frontierPaths.add(newNodePath);
                    } else {
                        // We found a node that's already in the frontier
                        int unknownNodeFrontierIndex = frontier.indexOf(unknownNode);
                        List<Integer> unknownNodeFrontierPath = frontierPaths.get(unknownNodeFrontierIndex);
                        if (newNodePath.size() < unknownNodeFrontierPath.size()) {
                            frontierPaths.set(unknownNodeFrontierIndex,newNodePath);
                        } else if (newNodePath.size() == unknownNodeFrontierPath.size()){
                            boolean thisPathIsBetter = false;
                            for (int i = 0; i < newNodePath.size(); i++) {
                                if (newNodePath.get(i) > unknownNodeFrontierPath.get(i)) {
                                    break;
                                } else if (newNodePath.get(i) < unknownNodeFrontierPath.get(i)) {
                                    thisPathIsBetter = true;
                                }
                                if (thisPathIsBetter) {
                                    frontierPaths.set(unknownNodeFrontierIndex,newNodePath);
                                }
                            }
                        }
                    }
                }
            }
            done = frontier.size()==0;


        }
        List<Integer> multicastPath = new ArrayList<>();
        for (List<Integer> path: exploredPaths) {
            for (Integer pathNode: path) {
                if (!multicastPath.contains(pathNode)) {
                    multicastPath.add(pathNode);
                }
            }
        }
        return multicastPath;
    }

    public List<Integer> getUnicastForwardingRoute(int firstIP, int secondIP) {
        List<Integer> explored = new ArrayList<>();
        List<List<Integer>> exploredPaths = new ArrayList<>();

        List<Integer> frontier = new ArrayList<>();
        List<List<Integer>> frontierPaths = new ArrayList<>();

        for (int i = 0; i <= highest_assigned_ip; i++) {
            if (i!=firstIP && longTopology[firstIP][i]){
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

            List<Integer> newNodePath = nodePath.subList(0,nodePath.size());
            newNodePath.add(node_to_explore);


            for (int unknownNode = 0; unknownNode <= highest_assigned_ip; unknownNode++) {
                if (unknownNode!=node_to_explore && longTopology[node_to_explore][unknownNode] && !explored.contains(unknownNode)){
                    // We found a path to a node we haven't explored yet
                    if (!frontier.contains(unknownNode)) {
                        frontier.add(unknownNode);
                        frontierPaths.add(newNodePath);
                    } else {
                        // We found a node that's already in the frontier
                        int unknownNodeFrontierIndex = frontier.indexOf(unknownNode);
                        List<Integer> unknownNodeFrontierPath = frontierPaths.get(unknownNodeFrontierIndex);
                        if (newNodePath.size() < unknownNodeFrontierPath.size()) {
                            frontierPaths.set(unknownNodeFrontierIndex,newNodePath);
                        } else if (newNodePath.size() == unknownNodeFrontierPath.size()){
                            boolean thisPathIsBetter = false;
                            for (int i = 0; i < newNodePath.size(); i++) {
                                if (newNodePath.get(i) > unknownNodeFrontierPath.get(i)) {
                                    break;
                                } else if (newNodePath.get(i) < unknownNodeFrontierPath.get(i)) {
                                    thisPathIsBetter = true;
                                }
                                if (thisPathIsBetter) {
                                    frontierPaths.set(unknownNodeFrontierIndex,newNodePath);
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
        int resultNumber = 0;
        for (int i = 0; i < shortTopology.length; i++) {
            // start from left
            resultNumber |= (shortTopology[i]?1:0) << 5-i;
        }
        return resultNumber;
    }

    public void saveTopology(int receivedTopology) {
        shortTopology = new boolean[6];
        longTopology = new boolean[4][4];
        for (int i = 0; i < shortTopology.length; i++) {
            shortTopology[i] = ( ( receivedTopology & (1 << (5-i)) ) >> (5-i) ) == 1;
        }

        updateLongTopologyFromShortTopology();
    }

    public int mergeTopologies(List<Integer> topologyNumbers) {
        List<boolean[]> shortTopologies = new ArrayList<>();
        List<boolean[][]> longTopologies = new ArrayList<>();
        boolean[][] resultTopology = new boolean[4][4];

        for (int i = 0; i < topologyNumbers.size(); i++) {
            boolean[] currentShortTopology = new boolean[6];
            boolean[][] currentlongTopology = new boolean[4][4];
            int currentTopologyNumber = topologyNumbers.get(i);
            for (int j = 0; j < currentShortTopology.length; j++) {
                currentShortTopology[j] = ( ( currentTopologyNumber & (1 << (5-i)) ) >> (5-i) ) == 1;
            }
            shortTopologies.add(currentShortTopology);

            for (int k = 0; k <= highest_assigned_ip; k++) {
                for (int l = 0; l <= highest_assigned_ip; l++) {
                    if (k==l) {
                        currentlongTopology[k][l]=true;
                    }
                    if (k==0 && l>0) {
                        currentlongTopology[0][l] = currentShortTopology[l-1];
                    }
                    if (k>0 && l<k) {
                        currentlongTopology[k][l] = currentlongTopology[l][k];
                    }
                    if (k==1 && l>1) {
                        currentlongTopology[1][l] = currentShortTopology[l+1];
                    }
                    if (k==2 && l > 2) {
                        currentlongTopology[2][l] = currentShortTopology[l+2];

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
                if (k==l) {
                    resultTopology[k][l]=true;
                }
                if (resultTopology[k][l] != resultTopology[l][k]) {
                    resultTopology[k][l] = false;
                    resultTopology[l][k] = false;
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
        checkRoutingTableExpirations();
        neighbor_available[sourceIP] = false; // never list ourselves as neighbor
        neighbor_available[neighborIP] = true;
        int delay = 40*1000;
        neighbor_expiration_time[neighborIP] = System.currentTimeMillis() + delay;

        Timer neighbor_expiration_timer = new Timer(delay + 1, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) { // TODO welke delay
                checkRoutingTableExpirations();
            }
        });
        neighbor_expiration_timer.setRepeats(false); // Only execute once
        neighbor_expiration_timer.start(); // Go go go!
        updateTopologyFromAvailableNeighbors();
    }

    public void updateLongTopologyFromShortTopology() {
        for (int i = 0; i <= highest_assigned_ip; i++) {
            for (int j = 0; j <= highest_assigned_ip; j++) {
                if (i==j) {
                    longTopology[i][j]=true;
                }
                if (i==0 && j>0) {
                    longTopology[0][j] = shortTopology[j-1];
                }
                if (i>0 && j<i) {
                    longTopology[i][j] = longTopology[j][i];
                }
                if (i==1 && j>1) {
                    longTopology[1][j] = shortTopology[j+1];
                }
                if (i==2 && j > 2) {
                    longTopology[2][j] = shortTopology[j+2];

                }
            }
        }
    }

    public void updateShortTopologyFromLongTopology() {
        for (int i = 0; i <= highest_assigned_ip; i++) {
            for (int j = 0; j <= highest_assigned_ip; j++) {
                if (i==0 && j>0) {
                    shortTopology[j-1] = longTopology[0][j];
                }
                if (i==1 && j>1) {
                    shortTopology[j+1] = longTopology[1][j];
                }
                if (i==2 && j > 2) {
                    shortTopology[j+2] = longTopology[2][j];

                }
            }
        }
    }

    public void updateTopologyFromAvailableNeighbors() {
        // Update the relevant row in matrix
        for (int i = 0; i <= highest_assigned_ip; i++) {
            if (i!= sourceIP) {
                longTopology[sourceIP][i] = neighbor_available[i];
            }
        }
        // Because it's symmetric: mirror the matrix, and make sure the diagonal is always true
        for (int i = 0; i <= highest_assigned_ip; i++) {
            for (int j = 0; j <= highest_assigned_ip; j++) {
                if (i==j) {
                    longTopology[i][j]=true;
                }
                if (i>0 && j<i) {
                    longTopology[i][j] = longTopology[j][i];
                }
            }
        }
        updateShortTopologyFromLongTopology();
    }




    public int getMulticastForwardingRouteNumber(int ip, List<Integer> route_ips) {
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
        all_ips.remove(ip);

        int first_hop = route_ips.size() > 0? route_ips.get(0) : -1;
        int second_hop = route_ips.size() > 1? route_ips.get(1) : -1;

        int first_hop_index = all_ips.indexOf(first_hop);
        int second_hop_index = all_ips.indexOf(second_hop);

        return Mathematics.encodePermutationOfThree(first_hop_index,second_hop_index);
    }

    public List<Integer> getMulticastForwardingRouteFromOrder(int ip, int order) {
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
        all_ips.remove(ip);
        return Mathematics.decodePermutationOfThree(order, all_ips);
    }

    public int getUnicastScheme(int sourceIP) {
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
        int[] unicast_route_number = new int[3];
        all_ips.remove(sourceIP);
        unicastRoutes.clear();
        for (int i = 0; i < all_ips.size(); i++) {
            int destinationIP = all_ips.get(i);
            if (destinationIP<=highest_assigned_ip) {
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
        return unicast_route_number[0]*5*5  + unicast_route_number[1]*5 + unicast_route_number[2];
    }

}
