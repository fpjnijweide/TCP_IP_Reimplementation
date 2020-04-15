package framework;


import client.Client;
import client.Message;
import client.MessageType;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static java.lang.Thread.sleep;

/**
 * This is just some example code to show you how to interact
 * with the server using the provided client and two queues.
 * Feel free to modify this code in any way you like!
 */


public class MyProtocol {
    // The host to connect to. Set this to localhost when using the audio interface tool.
    private static final String SERVER_IP = "netsys2.ewi.utwente.nl"; //"127.0.0.1";
    // The port to connect to. 8954 for the simulation server.
    private static final int SERVER_PORT = 8954;
    // The frequency to use.
    private static final int frequency = 5400;

    // Buffers of packets for various phases in the access control
    private final List<SmallPacket> receivedNegotiationPackets = new ArrayList<>();
    private final List<SmallPacket> receivedRequestPackets = new ArrayList<>();
    private final List<SmallPacket> forwardedPackets = new ArrayList<>();

    private boolean alwaysBroadcast = false; // If the user types ALL in the UI for which IP to send to, it is set to true
    List<Integer> timeslotsRequested; // Used for storing the timeslots requested per user for sending data
    List<BigPacket> dataPhaseBigPacketBuffer = new ArrayList<>(); // Used for storing packets we want to send but when we are in the wrong access control phase
    List<SmallPacket> dataPhaseSmallPacketBuffer = new ArrayList<>(); // Used for storing packets we want to send but when we are in the wrong access control phase
    Timer timer;
    Routing routing;
    PacketHandling packetHandling;
    private int tiebreaker; // Used for negotiating (DHCP). If another user rolls the same tiebreaker number, neither gets an IP.
    private long interferenceDetectionTimer; // Time the channel was last free is stored here to detect interference
    private int negotiationPhaseMasterLength = 8;
    private int negotiationPhaseStrangerLength;
    private int negotiationPhasesEncountered = 0;
    private int currentMasterNodeIP;
    private int exponentialBackoffFactor = 1;
    private State state = State.READY; // State is used for access control
    ReliableDelivery reliableDelivery;
    private int destIP; // What IP you want to send to


    public MyProtocol(int inputSourceIP) {
        routing = new Routing(); // Object with routing information and methods
        routing.sourceIP = inputSourceIP;
        BlockingQueue<Message> receivedQueue = new LinkedBlockingQueue<>();
        BlockingQueue<Message> sendingQueue = new LinkedBlockingQueue<>();
        packetHandling = new PacketHandling(sendingQueue); // Object with info and methods for packet assembly and disassembly
        reliableDelivery = new ReliableDelivery(packetHandling); // Object with info and methods for TCP

        destIP = getDestNode(routing.sourceIP); // Starts a UI window that asks you where you want to send to
        if (destIP == -1) {
            destIP = 0;
            alwaysBroadcast = true;
        }

        new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue); // Give the client the Queues to use
        new receiveThread(receivedQueue).start(); // Start thread to handle received messages!
        // handle sending from stdin from this thread.
        try {
            while (true) {
                handleInput(); // Method for handling input. This thread only does user input, networking happens on other thread
            }
        } catch (InterruptedException | IOException e) {
            System.exit(2);
        }
    }

    public static void main(String[] args) {
        int sourceIP = -1;
        if (args.length > 0) {
            //frequency = Integer.parseInt(args[0]);

            sourceIP = Integer.parseInt(args[0]);
            System.out.println("source IP is: " + sourceIP);
        }
        new MyProtocol(sourceIP);
    }

    public void setState(State state) {
        this.state = state;
        System.out.println("NEW STATE: " + state.toString());
    }

    public int getDestNode (int sourceIP){
        //dialog box to input destination node
        String destinationNode = JOptionPane.showInputDialog(null,"Hello, you are node " + sourceIP + ". Enter destination node (or type ALL for multicast): ");

        return destinationNode.equals("ALL")? -1 : Integer.parseInt(destinationNode);
    }

    public void handleInput() throws IOException, InterruptedException {
        // Handle user input
        ByteBuffer temp = ByteBuffer.allocate(1024);
        int read;
        read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
        System.out.println(read - 1);

        if (read > 0) {
            ByteBuffer text = ByteBuffer.allocate(read - 1);
            text.put(temp.array(), 0, read - 1);
            String textString = new String(text.array(), StandardCharsets.US_ASCII);
            if (state ==State.READY) { // If you did not type DISCOVERY, you are in the standard, non-access control phase
                switch (textString) {
                    case "DISCOVERY": // If you type DISCOVERY, we go to the discovery phase
                        startDiscoveryPhase(exponentialBackoffFactor);
                        break;
                    case "DISCOVERYNOW": // type this to skip the initial delay and send out the discovery packet instantly
                        startDiscoveryPhase(0);
                        break;
                    case "SMALLPACKET": // Send out a small packet
                        packetHandling.sendSmallPacket(new SmallPacket(routing.sourceIP, destIP, 0, false, false, false, false, alwaysBroadcast));
                        break;
                    default: // If you type something else, deliver
                        reliableDelivery.TCPsend(read, text,routing,packetHandling,destIP,alwaysBroadcast);
                        break;

                }
            } else { // If you are using medium access control, we do not use TCP
                // Instead, queue the packets up and send them in the data phase
                for (int i = 0; i < read - 1; i += 28) {
                    byte[] partial_text = read - 1 - i > 28 ? new byte[28] : new byte[read - 1 - i];
                    System.arraycopy(text.array(), i, partial_text, 0, partial_text.length);
                    boolean morePacketsFlag = read - 1 - i > 28;
                    int size = morePacketsFlag ? 32 : read - 1 - i + 4;
                    dataPhaseBigPacketBuffer.add(new BigPacket(routing.sourceIP, destIP, 0, false, false, false, false, true, partial_text, 0, morePacketsFlag, size, 0));
                }
            }
        }


    }



    private void startDiscoveryPhase(int exponential_backoff) {
        // Resets variables, and waits to see if there is an established network nearby. After a while, tries to start a network itself
        // By sending a discovery packet. If nobody responds to this discovery packet, it is probably okay to start a network.
        // This is basically ALOHA. The first person to successfully send a packet becomes the master node and will synchronize
        // timeslots so that we can transition to the more efficient slotted ALOHA for IP assignment, and then, when everybody
        // has an IP, use requests to be allotted timeslots for sending data
        setState(State.DISCOVERY);
        routing.highest_assigned_ip = -1;
        routing.sourceIP = -1;
        routing.shortTopology = new boolean[6];
        routing.longTopology = new boolean[4][4];
        tiebreaker = new Random().nextInt(1 << 7);
        timer = new Timer(new Random().nextInt(2000 * exponential_backoff + 1) + 8000 * exponential_backoff, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.DISCOVERY) {
                    try {
                        startSentDiscoveryPhase();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void startSentDiscoveryPhase() throws InterruptedException {
        // We successfully send a discovery packet. If nobody stops us in the next 2 seconds, we become the master node and start access control.
        SmallPacket packet = new SmallPacket(0, 0, tiebreaker, false, true, true, false, true);
        packetHandling.sendSmallPacket(packet);
        setState(State.SENT_DISCOVERY);
        timer = new Timer(2000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.SENT_DISCOVERY) {
                    try {
                        routing.sourceIP = 0; // We now have an actual IP, 0! -1 is the IP assigned to nodes that are not in any network.
                        routing.highest_assigned_ip = 0;
                        routing.shortTopology = new boolean[6]; // Reset network topology knowledge because we just started a new one.
                        routing.longTopology = new boolean[4][4];
                        startTimingMasterPhase(8);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void startTimingMasterPhase(int new_negotiation_phase_length) throws InterruptedException {
        // Starts timing master phase. This node has assigned an IP to itself. Send out a packet to announce that the negotiation (DHCP-like IP assignment) phase will begin
        currentMasterNodeIP = routing.sourceIP;
        setState(State.TIMING_MASTER);
        if (new_negotiation_phase_length > 0) {
            this.negotiationPhaseMasterLength = new_negotiation_phase_length;
        }
        exponentialBackoffFactor = 1;
        if (new_negotiation_phase_length == 0 && this.negotiationPhaseMasterLength > 1) { // If we are using an old phase length number that is above 1
            this.negotiationPhaseMasterLength /= 2; // Negotiation phase should be large in the beginning but smaller and smaller on later
        }

        SmallPacket packet = new SmallPacket(routing.sourceIP, 0, this.negotiationPhaseMasterLength, false, false, false, true, true);
        packetHandling.sendSmallPacket(packet);
        startNegotiationMasterPhase();
    }

    private void startTimingSlavePhase(int new_negotiation_phase_length, int new_master_ip) {
        // We received a packet that shows someone else is in the timing master phase. We already have an IP. Just wait for the negotiation phase to be over.
        // Once we receive a packet that shows the request phase will begin soon, transition to POST_NEGOTIATION_SLAVE

        if (new_negotiation_phase_length == 0 && this.negotiationPhaseMasterLength > 1) { // If we are using an old phase length number that is above 1
            this.negotiationPhaseMasterLength /= 2;
        }
        setState(State.TIMING_SLAVE);
        exponentialBackoffFactor = 1;
        timer = new Timer(10000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.TIMING_SLAVE) {
                    exponentialBackoffFactor = 1;
                    startDiscoveryPhase(exponentialBackoffFactor);

                }
            }
        });
        timer.setRepeats(false);
        timer.start();

    }

    private void startTimingStrangerPhase(int negotiation_length) {
        // We received a packet that shows someone else is in the timing master phase. We don't have an IP yet.
        // The negotiation phase will start soon, our chance to get an IP.
        setState(State.TIMING_STRANGER);
        exponentialBackoffFactor = 1;
        this.negotiationPhaseStrangerLength = negotiation_length;
        try {
            startNegotiationStrangerPhase();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private void startWaitingForTimingStrangerPhase() {
        // If we failed at negotiating for an IP, wait for the next timing phase.
        setState(State.WAITING_FOR_TIMING_STRANGER);
        exponentialBackoffFactor = 1;
        timer = new Timer(20000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.WAITING_FOR_TIMING_STRANGER) {
                    exponentialBackoffFactor = 1;
                    startDiscoveryPhase(exponentialBackoffFactor);
                }
            }
        });
        timer.setRepeats(false);
        timer.start();

    }

    private void startNegotiationMasterPhase() {
        // We, the master node, have just sent out the timing phase packet and are now entering the negotiation phase
        // All we do here is queue incoming negotiation packets to use them later, during the post_negotiation master phase
        setState(State.NEGOTIATION_MASTER);
        receivedNegotiationPackets.clear();
        Timer timer = new Timer(this.negotiationPhaseMasterLength * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                try {
                    startPostNegotiationMasterPhase();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void startNegotiationStrangerPhase() throws InterruptedException {
        // We do not have an IP, but a node next to us has opened the negotiation phase.
        // We basically use slotted ALOHA to try and send a packet here.
        // If we succeed in sending a packet, hopefully there was no interference and nobody used the same identifier as we did
        //

        tiebreaker = new Random().nextInt(1 << 5); // This is our "unique" 5-bit identifier (because we don't have a MAC address)
                                                      // if we are assigned an IP address, the master node will relay this identifier back to us
                                                      // if someone else used the same identifier, we don't get an IP (conflict / tie)
        setState(State.NEGOTIATION_STRANGER);
        this.negotiationPhasesEncountered++;
        for (int i = 0; i < this.negotiationPhaseStrangerLength; i++) {
            if (state != State.NEGOTIATION_STRANGER) {
                return;
            }
            float roll = new Random().nextFloat() * (1 + ((float) this.negotiationPhasesEncountered - 1) / 10);
            if (roll > 0.25) {
                SmallPacket packet = new SmallPacket(0, 0, tiebreaker, false, false, true, false, true);
                packetHandling.sendSmallPacket(packet);
                startNegotiationStrangerDonePhase(); // Stop trying to send packets in these timeslots, we succeeded. Wait to see if we get an IP.
                return;
            } else {
                sleep(packetHandling.SHORT_PACKET_TIMESLOT);
            }
        }
        startWaitingForTimingStrangerPhase(); // We did not succeed in sending a packet. Wait for next timing phase.

    }

    private void startNegotiationStrangerDonePhase() {
        // Negotiation phase is over and we might be getting an IP soon. Wait for the post-negotiation announcement packet.
        setState(State.NEGOTIATION_STRANGER_DONE);
        timer = new Timer(30000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.NEGOTIATION_STRANGER_DONE) {
                    startWaitingForTimingStrangerPhase();
                }
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void startPostNegotiationMasterPhase() throws InterruptedException {
        // Negotiation phase is over. Assign IPs to nodes, specify which nodes must participate in multicast forwarding, and specify the length of the request phase.
        setState(State.POST_NEGOTIATION_MASTER);

        List<Integer> route_ips = routing.getMulticastForwardingRoute(routing.sourceIP);
        int route = routing.getMulticastForwardingRouteNumber(routing.sourceIP, route_ips); // This packet specifies which nodes will participate in forwarding multicast packets, in what order

        int hops = 0; // Needed for proper forwarding
        int first_packet_ack_nr = hops << 6 | route;
        SmallPacket first_packet = new SmallPacket(routing.sourceIP, routing.sourceIP, first_packet_ack_nr, true, false, true, false, true);
        packetHandling.sendSmallPacket(first_packet);

        sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT); // Wait for rebroadcast to finish.
        List<Integer> received_tiebreakers = new ArrayList<>();

        // Make list of all tiebreakers
        for (SmallPacket negotiation_packet : receivedNegotiationPackets) {
            int received_tiebreaker = (negotiation_packet.ackNum & 0b0011111);
            received_tiebreakers.add(received_tiebreaker);
        }

        // If we find a packet that has a tie, add it to the list of packets to be removed
        List<SmallPacket> toRemove = new ArrayList<>();
        for (int i = 0; i < received_tiebreakers.size(); i++) {
            int current_tiebreaker = received_tiebreakers.get(i);
            if (Collections.frequency(received_tiebreakers, current_tiebreaker) > 1) {
                toRemove.add(receivedNegotiationPackets.get(i));
            }
        }

        receivedNegotiationPackets.removeAll(toRemove);

        for (SmallPacket negotiation_packet : receivedNegotiationPackets) {
            // Promote all the strangers that sent negotiation packets to slaves by giving them an IP. Multicast this information.
            int new_ip = routing.highest_assigned_ip + 1;
            int received_tiebreaker = (negotiation_packet.ackNum & 0b0011111);

            SmallPacket promotionPacket = new SmallPacket(routing.sourceIP, new_ip, received_tiebreaker | (hops << 5), true, false, true, false, true);
            packetHandling.sendSmallPacket(promotionPacket);
            routing.highest_assigned_ip = new_ip;
            routing.updateNeighbors(new_ip);
            sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT);
        }
        receivedNegotiationPackets.clear();

        int unicast_scheme = routing.getUnicastScheme(routing.sourceIP); // Specifies (for each node) which IPs participate in unicast forwarding to the master node. Encoded as a 7-bit number.
        // In the final packet, we announce the length of the request phase (indirectly by specifying the unicast route from each person)
        // It is assumed that each node will send a request packet, even when they do not have any data packets to send.
        SmallPacket final_packet = new SmallPacket(routing.sourceIP, hops, unicast_scheme, true, false, true, true, true);
        packetHandling.sendSmallPacket(final_packet);

    }

    private void startPostNegotiationSlavePhase(SmallPacket packet) throws InterruptedException {
        // We just received a packet announcing the end of the negotiation phase. Get ready for more packets.
        setState(State.POST_NEGOTIATION_SLAVE);
        int hops = (packet.ackNum & 0b1100000) >> 5;
        int multicastSchemeNumber = packet.ackNum & 0b0011111;

        // This packet specifies the multicast forwarding scheme of the master node.
        routing.postNegotiationSlaveforwardingScheme = routing.getMulticastForwardingRouteFromOrder(packet.sourceIP, multicastSchemeNumber);

        if (hops == 0) routing.updateNeighbors(packet.sourceIP); // If this packet went through 0 hops, add its source IP to your neighbors


        if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
            // We have to forward the packet in this timeslot
            packet.ackNum += (1 << 5);
            packetHandling.sendSmallPacket(packet);
        }

    }

    private void startPostNegotiationStrangerPhase(SmallPacket packet) {
        // The negotiation phase has ended. Wait for more packets (receiving side happens in the processMessage method)
        setState(State.POST_NEGOTIATION_STRANGER);
        int hops = (packet.ackNum & 0b1100000) >> 5;
        int multicastSchemeNumber = packet.ackNum & 0b0011111;
        routing.postNegotiationSlaveforwardingScheme = routing.getMulticastForwardingRouteFromOrder(packet.sourceIP, multicastSchemeNumber);
    }

    public void startRequestMasterPhase() {
        // Request phase. All we do now is receive and queue request packets, which state how many data packets a node wants to s send.
        setState(State.REQUEST_MASTER);
        receivedRequestPackets.clear();
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        all_ips.remove(currentMasterNodeIP);
        int request_phase_length = 0;

        for (int i = routing.sourceIP; i < all_ips.size(); i++) {
            if (all_ips.get(i) <= routing.highest_assigned_ip) {
                request_phase_length += 1; // Timeslot that a node sends.
                request_phase_length += routing.unicastRoutes.get(i).size();
            }

        }

        // Go to post request phase after a while
        Timer timer = new Timer(request_phase_length * packetHandling.SHORT_PACKET_TIMESLOT + 500, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                try {
                    startPostRequestMasterPhase();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go g

    }

    public void startRequestSlavePhase() {
        // Request phase. We must send a packet containing the amount of timeslots we want for sending
        setState(State.REQUEST_SLAVE);
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        all_ips.remove(currentMasterNodeIP);
        int thisNodesSendTurn = all_ips.get(routing.sourceIP); // Of the (max 3) nodes that should send request packets, when is our turn?
        int thisNodesSendTimeslot = thisNodesSendTurn;

        for (int i = 0; i < thisNodesSendTurn; i++) {
            thisNodesSendTimeslot += routing.unicastRoutes.get(i).size(); // Add the amount of forwarding timeslots needed to figure out which exact timeslot we should send in
        }

        // Wait that amount of timeslots
        Timer timer = new Timer(thisNodesSendTimeslot * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                try {
                    requestSlavePhaseSecondPart(); // Send our request packet using this method
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        timer.setRepeats(false);
        timer.start(); // Go go g


    }

    public void requestSlavePhaseSecondPart() throws InterruptedException {
        // Sends request packet
        int how_many_timeslots_do_we_want = howManyTimeslotsDoWeWant();
        int bit_1_and_2 = (how_many_timeslots_do_we_want & 0b1100) >> 2;
        boolean bit_3 = ((how_many_timeslots_do_we_want & 0b0010) >> 1) == 1;
        boolean bit_4 = (how_many_timeslots_do_we_want & 0b0001) == 1;

        int link_topology_bits = routing.getLinkTopologyBits();

        SmallPacket packet = new SmallPacket(routing.sourceIP, bit_1_and_2, link_topology_bits, bit_3, true, false, bit_4, false);
        packetHandling.sendSmallPacket(packet);

//        int delay_until_post_request_phase = 0;
//
//        for (int i = sourceIP; i < all_ips.size(); i++) {
//            if (all_ips.get(i)<=routing.highest_assigned_ip) {
//                if (i>sourceIP) {
//                    delay_until_post_request_phase += 1; // Timeslot that a node sends.
//                }
//                delay_until_post_request_phase += routing.unicastRoutes.get(i).size();
//            }
//
//        }

    }

    private int howManyTimeslotsDoWeWant() {
        // For each large packet, we want 1 + <amount of nodes in path to destination> timeslot
        // For each small packet, we want 1/6th of that.
        // Cap at 15.
        float result = 0;
        for (BigPacket bigPacket : dataPhaseBigPacketBuffer) {
            result += 1;
            int forwardingTime;
            if (!bigPacket.broadcast) {
                forwardingTime = routing.getUnicastForwardingRoute(bigPacket.sourceIP, bigPacket.destIP).size();
            } else {
                forwardingTime = routing.getMulticastForwardingRoute(routing.sourceIP).size();
            }
            result += forwardingTime;
        }
        for (SmallPacket smallPacket : dataPhaseSmallPacketBuffer) {
            result += ((float) 1 / (float) 6);
            int forwardingTime;
            if (!smallPacket.broadcast) {
                forwardingTime = routing.getUnicastForwardingRoute(smallPacket.sourceIP, smallPacket.destIP).size();
            } else {
                forwardingTime = routing.getMulticastForwardingRoute(routing.sourceIP).size();
            }
            result += ((float) forwardingTime) / 6;
        }
        int resultAsInt = (int) Math.ceil(result);
        return Math.min(resultAsInt, 15);
    }

    private void startPostRequestMasterPhase() throws InterruptedException {
        // Collect all the request packets (which contain the network topology as well)
        // Assemble 2 packets which contain the requested amount of timeslots for each node,
        // and a merged topology that everyone can use
        setState(State.POST_REQUEST_MASTER);
        int how_many_timeslots_do_we_want = howManyTimeslotsDoWeWant();
        List<Integer> topologyNumbers = new ArrayList<>();

        timeslotsRequested = new ArrayList<>();
        for (SmallPacket packet : receivedRequestPackets) {
            int timeslots = (packet.destIP << 2) | ((packet.ackFlag ? 1 : 0) << 1) | (packet.SYN ? 1 : 0); // Get timeslot info from packet
            timeslotsRequested.add(timeslots); // Add to array
            int topologyNumber = packet.ackNum & 0b111111; // get topology info from packet
            topologyNumbers.add(topologyNumber); // add to array
        }

        topologyNumbers.add(routing.sourceIP, routing.getLinkTopologyBits()); // add our own topology to this list of received topologies

        timeslotsRequested.add(routing.sourceIP, how_many_timeslots_do_we_want); // Adding our own request for timeslots

        // Pad these arrays with zeroes to avoid problems
        while (timeslotsRequested.size() < 4) {
            timeslotsRequested.add(0); //
        }
        while (topologyNumbers.size() < 4) {
            topologyNumbers.add(0); //
        }
        int hops = 0;

        // Synthesize the two packets that we will send. Both of them together contain the information on the data phase length, and the topology.
        boolean packet1_ackflag = ((timeslotsRequested.get(0) & 0b1000) >> 3) == 1;
        boolean packet1_synflag = ((timeslotsRequested.get(0) & 0b0100) >> 2) == 1;
        int packet1_bits_3_and_4 = (timeslotsRequested.get(0) & 0b0011);
        int topology = routing.mergeTopologies(topologyNumbers);
        int ackField = ((timeslotsRequested.get(1) & 0b1000) << 3) | topology;
        SmallPacket first_packet = new SmallPacket(hops, packet1_bits_3_and_4, ackField, packet1_ackflag, true, false, packet1_synflag, true);

        int packet2_bits_3_and_4 = (timeslotsRequested.get(1) & 0b0011);

        boolean packet2_ackflag = (timeslotsRequested.get(1) & 0b0100) >> 2 == 1;
        boolean packet2_synflag = (timeslotsRequested.get(2) & 0b1000) >> 3 == 1;
        int packet2_acknum = ((timeslotsRequested.get(2) & 0b0111) << 4) | timeslotsRequested.get(3);

        SmallPacket second_packet = new SmallPacket(hops, packet2_bits_3_and_4, packet2_acknum, packet2_ackflag, true, false, packet2_synflag, true);

        List<Integer> route_ips = routing.getMulticastForwardingRoute(routing.sourceIP); // Get the multicast path for this

        packetHandling.sendSmallPacket(first_packet); // send packet and wait for rebroadcast
        sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT);
        packetHandling.sendSmallPacket(second_packet); // send packet and wait for rebroadcast
        sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT);

        receivedRequestPackets.clear();

        startDataPhase(); // Go to data phase. We are now no longer the master node, we are just a normal node sending data in our assigned timeslot.
    }

    private void startPostRequestSlavePhase(SmallPacket packet) {
        // Request phase has ended. Use the information from the two packets that come in to determine the length and specifics of the data phase
        // Such as: when are we allowed to send? Also, get the network topology from these packets
        setState(State.POST_REQUEST_SLAVE);
        forwardedPackets.clear();

        timeslotsRequested = new ArrayList<>(Arrays.asList(-1, -1, -1, -1));

        int hops = packet.sourceIP; // hops is stored in source IP due to lack of space.

        int received_topology = packet.ackNum & 0b0111111;
        int first_person_timeslots = ((packet.ackFlag ? 1 : 0) << 3) | ((packet.SYN ? 1 : 0) << 2) | packet.destIP;
        int second_person_first_bit = (packet.ackNum & 0b1000000) >> 3;
        routing.saveTopology(received_topology); // Save the topology that we received
        timeslotsRequested.set(0, first_person_timeslots);
        timeslotsRequested.set(1, second_person_first_bit);

        if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
            // You have to forward this time
            packet.sourceIP += 1;
            try {
                packetHandling.sendSmallPacket(packet);
                forwardedPackets.add(packet);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void startDataPhase() {
        // Calculate when we are allowed to send exactly
        setState(State.DATA_PHASE);
        forwardedPackets.clear();
        int delay_until_we_send = 0;
        int delay_after_we_send = 0;

        for (int i = 0; i < routing.sourceIP; i++) {
            delay_until_we_send += timeslotsRequested.get(i);
        }
        for (int i = routing.sourceIP + 1; i <= routing.highest_assigned_ip; i++) {
            delay_after_we_send += timeslotsRequested.get(i);
        }

        // Wait this amount of time
        final int finalDelay_after_we_send = delay_after_we_send;
        Timer timer = new Timer((delay_until_we_send * packetHandling.LONG_PACKET_TIMESLOT), new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                try {
                    dataPhaseSecondPart(finalDelay_after_we_send); // This is the method where we actually send things
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        timer.setRepeats(false);
        timer.start(); // Go go g

    }

    private void dataPhaseSecondPart(int delay_after_we_send) throws InterruptedException {
        // Send out our packets!
        for (SmallPacket packet : dataPhaseSmallPacketBuffer) {
            packetHandling.sendSmallPacket(packet);
            int forwardingTime;
            if (!packet.broadcast) {
                forwardingTime = routing.getUnicastForwardingRoute(packet.sourceIP, packet.destIP).size();
            } else {
                forwardingTime = routing.getMulticastForwardingRoute(packet.sourceIP).size();
            }
            sleep(forwardingTime * packetHandling.SHORT_PACKET_TIMESLOT);
        }
        dataPhaseSmallPacketBuffer.clear();

        for (BigPacket packet : dataPhaseBigPacketBuffer) {
            packetHandling.sendPacket(packet);
            int forwardingTime;
            if (!packet.broadcast) {
                forwardingTime = routing.getUnicastForwardingRoute(packet.sourceIP, packet.destIP).size();
            } else {
                forwardingTime = routing.getMulticastForwardingRoute(packet.sourceIP).size();
            }
            sleep(forwardingTime * packetHandling.LONG_PACKET_TIMESLOT);
        }
        dataPhaseBigPacketBuffer.clear();

        // Now wait for the data phase to end
        Timer timer = new Timer(delay_after_we_send * packetHandling.LONG_PACKET_TIMESLOT, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                try {
                    dataPhaseThirdPart();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        timer.setRepeats(false);
        timer.start(); // Go go g

    }

    public void dataPhaseThirdPart() throws InterruptedException {
        // Determine if we are a master or slave in the next timing phase.
        int next_master = (currentMasterNodeIP + 1) % (routing.highest_assigned_ip + 1);
        if (next_master == routing.sourceIP) {
            startTimingMasterPhase(0);
        } else {
            startTimingSlavePhase(0, next_master);
        }
    }

    private void processMessage(ByteBuffer data, MessageType type) {
        // Process input.
        byte[] bytes = new byte[0];
        if (data != null) {
            bytes = data.array();
        }
        switch (type) { // First, regardless of our current state, print the type of the packet we just received.
            case FREE:
                long delay = System.currentTimeMillis() - interferenceDetectionTimer;
                System.out.println("FREE. Delay since previous: " + delay);
                detectInterference(delay);
                break;
            case BUSY:
                System.out.println("BUSY");
                interferenceDetectionTimer = System.currentTimeMillis();
                break;
            case DATA:
                System.out.println("DATA");
                packetHandling.printByteBuffer(bytes,false);
                break;
            case DATA_SHORT:
                System.out.println("DATA_SHORT");
                packetHandling.printByteBuffer(bytes,false);
                break;
            case DONE_SENDING:
                System.out.println("DONE_SENDING");
                break;
            case HELLO:
                System.out.println("HELLO");
                break;
            case SENDING:
                System.out.println("SENDING");
                break;
            case END:
                System.out.println("END");
                System.exit(0);
                break;
        }
        switch (state) {
            case DISCOVERY:
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);

                    if (packet.broadcast && packet.negotiate && packet.request && !packet.ackFlag) { // another discovery packet
                        timer.stop();
                        exponentialBackoffFactor *= 2;
                        startDiscoveryPhase(exponentialBackoffFactor); // there is someone else trying to start a network as well. wait longer.
                    } else if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) { // timing master packet
                        timer.stop();
                        startTimingStrangerPhase(packet.ackNum); // someone has started a network. try to join them
                    }
                }
                break;
            case SENT_DISCOVERY:
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);

                    boolean other_discovery_packet = packet.broadcast && packet.negotiate && packet.request && !packet.ackFlag;
                    boolean discovery_denied_packet = packet.broadcast && packet.negotiate && packet.request && packet.ackFlag;

                    if ((other_discovery_packet && (tiebreaker <= packet.ackNum)) || (discovery_denied_packet && packet.ackNum == tiebreaker)) {
                        // if we got another discovery packet from someone else and they win the tiebreaker, go back to discovery phase
                        timer.stop();
                        exponentialBackoffFactor *= 2;
                        startDiscoveryPhase(exponentialBackoffFactor);
                    }

                    if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) {
                        // If someone else started a network, try to join them in the timing phase.
                        timer.stop();
                        startTimingStrangerPhase(packet.ackNum);
                    }
                }
                break;
            case TIMING_MASTER:
                // we are not planning to receive anything during this phase
                break;
            case TIMING_STRANGER:
                // we are not planning to receive anything during this phase
                break;
            case TIMING_SLAVE:
                // The data phase just ended. We are a slave node with an IP. We are going to wait for the negotiation phase to end, after which we can request timeslots again.
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                        // If we get a psot-negotiation packet, go to that phase.
                        if (!packet.SYN && packet.sourceIP == packet.destIP) {
                            timer.stop();
                            try {
                                startPostNegotiationSlavePhase(packet);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
                break;
            case NEGOTIATION_MASTER:
                // We are expecting neighbor nodes without IPs to send negotiation packets via slotted ALOHA
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && !packet.ackFlag && !packet.request) {
                        // If we get a negotiation packet, store it for later use
                        receivedNegotiationPackets.add(packet);
                    }
                }

                break;
            case NEGOTIATION_STRANGER:
                // we are not planning to receive anything during this phase
//                switch (type) {
//                    case DATA_SHORT:
//                        System.out.println("DATA_SHORT");
//                        packetHandling.printByteBuffer(bytes, false); //Just print the data
//                        SmallPacket packet = packetHandling.readSmallPacket(bytes);
//                        break;
//                }

                break;

            case WAITING_FOR_TIMING_STRANGER:
                // We failed to get an IP during negotiation. Wait for next timing phase.
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) {
                        // We got a timing phase packet.
                        timer.stop();
                        startTimingStrangerPhase(packet.ackNum);
                    }
                }
                break;
            case NEGOTIATION_STRANGER_DONE:
                // we might be getting an IP as we successfully sent a negotiation packet. Wait for post-negotiation packets to come in.
                if (type == MessageType.DATA_SHORT) {// wait for POST_NEGOTIATION packet, if we got it, go to POST_NEGOTIATION_STRANGER
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                        if (!packet.SYN && packet.sourceIP == packet.destIP) {
                            timer.stop();
                            startPostNegotiationStrangerPhase(packet);
                        }
                    }
                }
                break;
            case POST_NEGOTIATION_MASTER:
                // negotiation just ended. because we send many packets during this phase, we are going to wait for the
                // channel to become free, and only then do we start the request phase
                // while this makes the desynchronization of timeslots less extreme, it doesn't solve the problem.
                // most interference happens here because the 3 packets sent during post negotiation are queued too long
                if (type == MessageType.FREE) {
                    Timer timer = new Timer(routing.getMulticastForwardingRoute(routing.sourceIP).size() * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent arg0) {
                            startRequestMasterPhase();
                        }
                    });
                    timer.setRepeats(false);
                    timer.start(); // Go go g
                }
                break;
            case POST_NEGOTIATION_SLAVE:
                // waiting for the packets that show which nodes have been promoted and given an IP
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                        // we got a post-negotiation packet
                        if (!packet.SYN && packet.sourceIP != packet.destIP) {
                            // this packet shows a node has been given an ip
                            routing.highest_assigned_ip = packet.destIP;
                            int hops = packet.ackNum >> 5;
                            if (hops == 0) routing.updateNeighbors(packet.sourceIP);
                            if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
                                // You have to forward this time
                                packet.ackNum += (1 << 5);
                                try {
                                    packetHandling.sendSmallPacket(packet);
                                    forwardedPackets.add(packet);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else if (packet.SYN) {
                            finalPostNegotiationHandler(packet);
                        }
                    }
                }
                break;
            case POST_NEGOTIATION_STRANGER:
                // Same as post negotiation slave, but we assign ourselves an IP if we get it, and go back to WAITING_FOR_TIMING if we don't
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                        if (!packet.SYN && packet.sourceIP != packet.destIP) {
                            if (routing.sourceIP != -1) {
                                routing.highest_assigned_ip = packet.destIP;
                            }
                            if ((packet.ackNum & 0b0011111) == tiebreaker) {
                                // we have been given an IP!!
                                routing.sourceIP = packet.destIP;
                                routing.highest_assigned_ip = packet.destIP;
                                currentMasterNodeIP = packet.sourceIP;
                                routing.updateNeighbors(currentMasterNodeIP);
                            }

                            int hops = packet.ackNum >> 5;
                            if (hops == 0) routing.updateNeighbors(packet.sourceIP);
                        } else if (packet.SYN) {
                            // Final post-negotiation packet has come in, handle it.
                            if (routing.sourceIP != -1) {
                                finalPostNegotiationHandler(packet);
                            } else {
                                // Finished POST_NEGOTIATION_STRANGER without an IP. This means we lost the tiebreaker. Go back to waiting.
                                timer.stop();
                                startWaitingForTimingStrangerPhase();
                            }
                        }
                    }
                }
                break;
            case REQUEST_MASTER:
                // Wait for request packets and store them
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (!packet.broadcast && !packet.negotiate && packet.request) {
                        receivedRequestPackets.add(packet);
                    }
                }
                break;
            case REQUEST_SLAVE:
                // Forward other request packets if we have to.
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
                    all_ips.remove(currentMasterNodeIP);
                    if (!packet.broadcast && !packet.negotiate && packet.request) {
                        // If we are in the route of the person that sent this packet

                        if (routing.unicastRoutes.get(all_ips.indexOf(packet.sourceIP)).contains(routing.sourceIP) && !forwardedPackets.contains(packet)) { // TODO THIS MIGHT NOT WORK
                            try {
                                packetHandling.sendSmallPacket(packet); // We have to forward it
                                forwardedPackets.add(packet);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    // If we see a post-request phase packet, that means this phase has ended
                    if (!packet.negotiate && packet.request && packet.broadcast) {
                        timer.stop();
                        startPostRequestSlavePhase(packet);
                    }
                }
                break;
            case POST_REQUEST_MASTER:
                // no incoming packets expected here
                break;
            case POST_REQUEST_SLAVE:
                // we are expecting a second packet from the master, this phase
                // the data from the first packet has already been stored during the startPostRequestSlavePhase method
                if (type == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (!packet.negotiate && packet.request && packet.broadcast) {
                        int second_person_requested_timeslot = timeslotsRequested.get(1) | ((packet.ackFlag ? 1 : 0) << 1) | packet.destIP;
                        int third_person_requested_timeslot = ((packet.SYN ? 1 : 0) << 7) | ((packet.ackNum & 0b01110000) >> 4);
                        int fourth_person_requested_timeslot = packet.ackNum & 0b1111;
                        timeslotsRequested.set(1, second_person_requested_timeslot);
                        timeslotsRequested.set(2, third_person_requested_timeslot);
                        timeslotsRequested.set(3, fourth_person_requested_timeslot);

                        // We now have information on the network topology and the assignment of timeslots in data phase

                        int hops = packet.sourceIP;
                        try {
                            if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
                                // You have to forward this time
                                packet.sourceIP += 1;
                                packetHandling.sendSmallPacket(packet);
                                // Wait the right amount of time until this phase ends (1 less than if we did not forward a packet)
                                Timer timer = new Timer((routing.postNegotiationSlaveforwardingScheme.size() - hops - 1) * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                                    @Override
                                    public void actionPerformed(ActionEvent arg0) {
                                        startDataPhase();
                                    }
                                });
                                timer.setRepeats(false);
                                timer.start(); // Go go g

                            } else {
                                // Wait the right amount of time until this phase ends (1 longer than if we had forwarded a packet)
                                Timer timer = new Timer((routing.postNegotiationSlaveforwardingScheme.size() - hops) * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                                    @Override
                                    public void actionPerformed(ActionEvent arg0) {
                                        startDataPhase();
                                    }
                                });
                                timer.setRepeats(false);
                                timer.start(); // Go go g
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }


                break;
            case DATA_PHASE:
                // data phase.
                // Forward packets if we are in their unicast or multicast path (the latter is only applicable for broadcast packets)
                // If they are addressed to us, display them.
                switch (type) {
                    case DATA_SHORT:
                        SmallPacket smallPacket = packetHandling.readSmallPacket(bytes);
                        if (smallPacket.broadcast) {
                            throw new RuntimeException("Small packets cannot be multicast because they lack a hops field");
                        }
                        // TODO packets should not have request,negotiation flags etc. make error handler method?
                        if (smallPacket.destIP == routing.sourceIP) {
                            handleSmallPacket(smallPacket);
                        } else if (!smallPacket.broadcast && routing.getUnicastForwardingRoute(smallPacket.sourceIP, smallPacket.destIP).contains(routing.sourceIP)) {
                            // we are on the route. forward this packet if we have to
                            if (!forwardedPackets.contains(smallPacket)) {
                                forwardedPackets.add(smallPacket);
                                try {
                                    packetHandling.sendSmallPacket(smallPacket);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        break;
                    case DATA:
                        BigPacket bigPacket = packetHandling.readBigPacket(bytes);
                        if (bigPacket.hops == 0) routing.updateNeighbors(bigPacket.sourceIP);
                        // TODO maybe use forwardedpackets again
                        // Forwarding (you might have to forward multicast packet even if you handle it)
                        if (!bigPacket.broadcast && routing.getUnicastForwardingRoute(bigPacket.sourceIP, bigPacket.destIP).contains(routing.sourceIP)) {
                            // we are on the route
                            if (bigPacket.hops == routing.getUnicastForwardingRoute(bigPacket.sourceIP, bigPacket.destIP).indexOf(routing.sourceIP)) {
                                // If this is a unicast pacet, we are on the route, and the amount of hops so far seems logical, forward it
                                forwardedPackets.add(bigPacket);
                                try {
                                    // forward packet with hops incremented (we might need the original nr later on though so we decrement afterward)
                                    bigPacket.hops += 1;
                                    packetHandling.sendPacket(bigPacket);
                                    bigPacket.hops -= 1;
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else if (bigPacket.broadcast && routing.getMulticastForwardingRoute(bigPacket.sourceIP).contains(routing.sourceIP)) {
                            // This is a multicast packet. If we are on the route
                            if (bigPacket.hops == routing.getMulticastForwardingRoute(bigPacket.sourceIP).indexOf(routing.sourceIP)) {
                                // And if the amount of hops so far seems logical: forward it
                                forwardedPackets.add(bigPacket);
                                try {
                                    bigPacket.hops += 1;
                                    packetHandling.sendPacket(bigPacket);
                                    bigPacket.hops -= 1;
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }

                        if (bigPacket.destIP == routing.sourceIP) {
                            handleBigPacket(bigPacket);
                        }
                        break;
                }

                break;
            case READY: // Normal, non access-control phase. Uses TCP-like protocol for delivering and receiving.
                switch (type) {
                    case DATA:
                        BigPacket packet = packetHandling.readBigPacket(bytes);
                        try {
                            reliableDelivery.TCPreceive(packet);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                        break;
                    case DATA_SHORT:
                        SmallPacket smallPacket = packetHandling.readSmallPacket(bytes);
                        reliableDelivery.TCPreceiveSmall(smallPacket);
                        break;

                }
        }

    }

    private void handleBigPacket(BigPacket bigPacket) {
        // Shows messages addressed to us in a fancy way to set it apart from debug info
        System.out.println("-------------------------");
        System.out.println("New message from " +bigPacket.sourceIP+ ": ");
        for (byte aByte:bigPacket.payloadWithoutPadding) {
            System.out.print((char) aByte);
        }
        System.out.println("-------------------------");
    }

    private void handleSmallPacket(SmallPacket smallPacket) {
        // Small packets don't contain any messages to show the user. Might still be useful to implement something here later.
    }

    private void finalPostNegotiationHandler(SmallPacket packet) {
        // Handles slave/stranger side of final post negotiation phase packet
        if (packet.SYN) {
            List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
            all_ips.remove(packet.sourceIP);
            currentMasterNodeIP = packet.sourceIP;

            // Converts the route number representation from base 10 to base 5, splitting it into three numbers
            int[] route_numbers = new int[]{(packet.ackNum / 25) % 5, (packet.ackNum / 5) % 5, packet.ackNum % 5};


            routing.unicastRoutes = new ArrayList<>();
            for (int i = 0; i < route_numbers.length; i++) {

                List<Integer> ip_list = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
                ip_list.remove(packet.sourceIP);

                ip_list.remove(i); // this will remove by index, not element
                int order = route_numbers[i];

                // Using some permutation magic this number can be translated into a lsit of IPs used for forwarding from this node to the master node
                List<Integer> unicastRoute = Mathematics.decodePermutationOfTwo(order, ip_list);

                routing.unicastRoutes.add(unicastRoute);
            }
            routing.unicastRouteToMaster = routing.unicastRoutes.get(all_ips.indexOf(routing.sourceIP)); // Get our forwarding path to master node

            int hops = packet.destIP;
            try {
                if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
                    // You have to forward this time
                    packet.destIP += 1;
                    packetHandling.sendSmallPacket(packet);
                    // Wait until the end of this phase (1 timeslot less because you already forwarded a packet, taking up a timeslot)
                    Timer timer = new Timer((routing.postNegotiationSlaveforwardingScheme.size() - hops - 1) * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent arg0) {
                            startRequestSlavePhase();
                        }
                    });
                    timer.setRepeats(false);
                    timer.start(); // Go go g

                } else {
                    // Wait until the end of this phase
                    Timer timer = new Timer((routing.postNegotiationSlaveforwardingScheme.size() - hops) * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent arg0) {
                            startRequestSlavePhase();
                        }
                    });
                    timer.setRepeats(false);
                    timer.start(); // Go go g
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }


        }
    }

    void detectInterference(long delay) {
        // Check if the expected delay from the packets we wanted to send is less than the actual delay (with some fault tolerance in the number already)
        // If there is a difference , there was interference because another node started transmitting, but not at exactly the same time.
        boolean interference = false;
        if (packetHandling.sending) {
            packetHandling.sending = false;
            int expectedDelay = 0;
            for (int i = 0; i < packetHandling.messagesJustSent.size(); i++) {
                MessageType type = packetHandling.messagesJustSent.get(i).getType();
                if (type == MessageType.DATA) {
                    expectedDelay += packetHandling.LONG_PACKET_TIMESLOT;
                } else {
                    expectedDelay += packetHandling.SHORT_PACKET_TIMESLOT;
                }

            }
            interference = delay > expectedDelay;

            if (interference) {
                System.out.println("\u001B[31mINTEFERFERENCE DETECTED\u001B[0m");


                if (packetHandling.messagesJustSent.get(0).getType() == MessageType.DATA_SHORT) {
                    SmallPacket packet = packetHandling.readSmallPacket(packetHandling.messagesJustSent.get(0).getData().array());
                    if (packet.negotiate && packet.broadcast && !packet.request && !packet.ackFlag) {
                        // Our negotiation packet had interference, get rid of it
                        packetHandling.messagesJustSent.clear();
                    } else if (packet.negotiate && packet.broadcast && packet.request && !packet.ackFlag) {
                        // Our discovery packet had interference
                        packetHandling.messagesJustSent.clear();
                        timer.stop();
                        exponentialBackoffFactor *= 2;
                        startDiscoveryPhase(exponentialBackoffFactor);
                    } else if (!packet.negotiate && !packet.request && packet.ackFlag) {
                        // ACK packet. Get rid of it
                        packetHandling.messagesJustSent.clear();
                    } else if (packet.broadcast && packet.SYN) {
                        packetHandling.messagesJustSent.clear();
                        // Timing master packet. Rebroadcast!
                        try {
                            timer.stop();
                            startTimingMasterPhase(negotiationPhaseMasterLength);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    System.out.println("Keeping DATA packet in buffer");
                    // TODO resend? TCP will do this for us
                }

            } else {
                if (state == State.NEGOTIATION_STRANGER) {
                    // If we had no interference when sending our negotiation packet, go to the next phase.
                    timer.stop();
                    startNegotiationStrangerDonePhase();
                }

                // Store the successfully sent packet in our history. Might be useful.
                if (packetHandling.messageHistory.size() >= 1000) {
                    packetHandling.messageHistory.remove(0);
                }
                packetHandling.messageHistory.addAll(packetHandling.messagesJustSent);
                packetHandling.messagesJustSent.clear();
            }
        }


    }

    private class receiveThread extends Thread {
        private final BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue) {
            super();
            this.receivedQueue = receivedQueue;
        }

        public void run() {
            while (true) {
                try {
                    Message m = receivedQueue.take();
                    processMessage(m.getData(), m.getType());
                } catch (InterruptedException e) {
                    System.err.println("Failed to take from queue: " + e);
                }
            }
        }
    }


}

