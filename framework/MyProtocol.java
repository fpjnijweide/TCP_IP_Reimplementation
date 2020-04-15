package framework;


import client.Client;
import client.Message;
import client.MessageType;

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
    private static final int frequency = 15400;
    private final List<SmallPacket> negotiatedPackets = new ArrayList<>();
    private final List<SmallPacket> requestPackets = new ArrayList<>();
    private final List<SmallPacket> forwardedPackets = new ArrayList<>();
    List<Integer> timeslotsRequested;
    List<BigPacket> dataPhaseBigPacketBuffer = new ArrayList<>();
    List<SmallPacket> dataPhaseSmallPacketBuffer = new ArrayList<>();
    Timer timer;
    Routing routing;
    PacketHandling packetHandling;
    private int tiebreaker;
    private long timeMilli;
    private int negotiationPhaseMasterLength = 8;
    private int negotiationPhaseStrangerLength;
    private int negotiationPhasesEncountered = 0;
    private int currentMaster;
    private int exponentialBackoff = 1;
    private State state = State.READY;

    public MyProtocol(int inputSourceIP) {
        routing = new Routing();
        routing.sourceIP = inputSourceIP;
        BlockingQueue<Message> receivedQueue = new LinkedBlockingQueue<>();
        BlockingQueue<Message> sendingQueue = new LinkedBlockingQueue<>();
        packetHandling = new PacketHandling(sendingQueue);

        new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue); // Give the client the Queues to use
        new receiveThread(receivedQueue).start(); // Start thread to handle received messages!
        // handle sending from stdin from this thread.
        try {
            while (true) {
                handleInput();
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

    @SuppressWarnings("ConstantConditions")
    public void handleInput() throws IOException, InterruptedException {
        ByteBuffer temp = ByteBuffer.allocate(1024);
        int read;
        read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
        System.out.println(read - 1);

        if (read > 0) {
            ByteBuffer text = ByteBuffer.allocate(read - 1); // jave includes newlines in System.in.read, so -2 to ignore this
            text.put(temp.array(), 0, read - 1); // java includes newlines in System.in.read, so -2 to ignore this
            String textString = new String(text.array(), StandardCharsets.US_ASCII);
            if (state ==State.READY) {
                switch (textString) {
                    case "DISCOVERY":
                        startDiscoveryPhase(exponentialBackoff);
                        break;
                    case "DISCOVERYNOW":
                        startDiscoveryPhase(0);
                        break;
                    case "SMALLPACKET":
                        packetHandling.sendSmallPacket(new SmallPacket(0, 0, 0, false, false, false, false, false));
                        break;
                    default:
                        for (int i = 0; i < read - 1; i += 28) {
                            byte[] partial_text = read - 1 - i > 28 ? new byte[28] : new byte[read - 1 - i];
                            System.arraycopy(text.array(), i, partial_text, 0, partial_text.length);
//                        for (int j = 0; j < partial_text.length; j++) {
//                            partial_text[j] = text.array()[j+i];
//                        }
                            // TODO @Martijn implement sliding window here, sending side

                            // TODO proper sequence number
                            // TODO last ack received als field bijhouden
                            // TODO last seq nr sent als field bjihouden
                            // TODO SWS bijhouden als field
                            // todo LAR+SWS (biggest sendable packet) bijhouden als field
                            // TODO hou list of booleans bij (als field) met welke packets al geACKt zijn (bij LAR increase: pop dingen aan begin en push FALSEs aan einde)


                            boolean morePacketsFlag = read - 1 - i > 28;
                            int size = morePacketsFlag ? 32 : read - 1 - i + 4;
                            packetHandling.sendPacket(new BigPacket(routing.sourceIP, 0, 0, false, false, false, false, true, partial_text, 0, morePacketsFlag, size, 0));

                        }
                        break;

                }
            } else {
                for (int i = 0; i < read - 1; i += 28) {
                    byte[] partial_text = read - 1 - i > 28 ? new byte[28] : new byte[read - 1 - i];
                    System.arraycopy(text.array(), i, partial_text, 0, partial_text.length);
                    boolean morePacketsFlag = read - 1 - i > 28;
                    int size = morePacketsFlag ? 32 : read - 1 - i + 4;
                    dataPhaseBigPacketBuffer.add(new BigPacket(routing.sourceIP, 0, 0, false, false, false, false, true, partial_text, 0, morePacketsFlag, size, 0));
                }
            }
        }


    }

    private void startDiscoveryPhase(int exponential_backoff) {
        setState(State.DISCOVERY);
        routing.highest_assigned_ip = -1;
        routing.sourceIP = -1;
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
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!
    }

    private void startSentDiscoveryPhase() throws InterruptedException {
        SmallPacket packet = new SmallPacket(0, 0, tiebreaker, false, true, true, false, true);
        packetHandling.sendSmallPacket(packet);
        setState(State.SENT_DISCOVERY);
        timer = new Timer(2000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.SENT_DISCOVERY) {
                    try {
                        routing.sourceIP = 0;
                        routing.highest_assigned_ip = 0;
                        routing.shortTopology = new boolean[6];
                        routing.longTopology = new boolean[4][4];
                        startTimingMasterPhase(8);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!
    }

    private void startTimingMasterPhase(int new_negotiation_phase_length) throws InterruptedException {
        currentMaster = routing.sourceIP;
        setState(State.TIMING_MASTER);
        if (new_negotiation_phase_length > 0) {
            this.negotiationPhaseMasterLength = new_negotiation_phase_length;
        }
        exponentialBackoff = 1;
        if (new_negotiation_phase_length == 0 && this.negotiationPhaseMasterLength > 1) { // If we are using an old phase length number that is above 1
            this.negotiationPhaseMasterLength /= 2;
        }

        SmallPacket packet = new SmallPacket(routing.sourceIP, 0, this.negotiationPhaseMasterLength, false, false, false, true, true);
        packetHandling.sendSmallPacket(packet);
        startNegotiationMasterPhase();
    }

    private void startTimingSlavePhase(int new_negotiation_phase_length, int new_master_ip) {
        if (new_negotiation_phase_length == 0 && this.negotiationPhaseMasterLength > 1) { // If we are using an old phase length number that is above 1
            this.negotiationPhaseMasterLength /= 2;
        }
        setState(State.TIMING_SLAVE);
        exponentialBackoff = 1;
        timer = new Timer(10000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.TIMING_SLAVE) {
                    exponentialBackoff = 1;
                    startDiscoveryPhase(exponentialBackoff);

                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!

    }

    private void startTimingStrangerPhase(int negotiation_length) {
        setState(State.TIMING_STRANGER);
        exponentialBackoff = 1;
        this.negotiationPhaseStrangerLength = negotiation_length;
        try {
            startNegotiationStrangerPhase();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private void startWaitingForTimingStrangerPhase() {
        setState(State.WAITING_FOR_TIMING_STRANGER);
        exponentialBackoff = 1;
        timer = new Timer(20000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.WAITING_FOR_TIMING_STRANGER) {
                    exponentialBackoff = 1;
                    startDiscoveryPhase(exponentialBackoff);
                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!

    }

    private void startNegotiationMasterPhase() {
        setState(State.NEGOTIATION_MASTER);
        negotiatedPackets.clear();
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
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!
    }

    private void startNegotiationStrangerPhase() throws InterruptedException {
        tiebreaker = new Random().nextInt(1 << 5);
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
                startNegotiationStrangerDonePhase();
                return;
            } else {
                sleep(packetHandling.SHORT_PACKET_TIMESLOT);
            }
        }
        startWaitingForTimingStrangerPhase();

    }

    private void startNegotiationStrangerDonePhase() {
        setState(State.NEGOTIATION_STRANGER_DONE);
        timer = new Timer(30000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state == State.NEGOTIATION_STRANGER_DONE) {
                    startWaitingForTimingStrangerPhase();
                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!
    }

    private void startPostNegotiationMasterPhase() throws InterruptedException {
        setState(State.POST_NEGOTIATION_MASTER);

        List<Integer> route_ips = routing.getMulticastForwardingRoute(routing.sourceIP);
        int route = routing.getMulticastForwardingRouteNumber(routing.sourceIP, route_ips);

        int hops = 0;
        int first_packet_ack_nr = hops << 6 | route;
        SmallPacket first_packet = new SmallPacket(routing.sourceIP, routing.sourceIP, first_packet_ack_nr, true, false, true, false, true);
        packetHandling.sendSmallPacket(first_packet);

        sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT);
        List<Integer> received_tiebreakers = new ArrayList<>();

        // Make list of all tiebreakers
        for (SmallPacket negotiation_packet : negotiatedPackets) {
            int received_tiebreaker = (negotiation_packet.ackNum & 0b0011111);
            received_tiebreakers.add(received_tiebreaker);
        }

        // If we find a packet that has a tie, add it to the list of packets to be removed
        List<SmallPacket> toRemove = new ArrayList<>();
        for (int i = 0; i < received_tiebreakers.size(); i++) {
            int current_tiebreaker = received_tiebreakers.get(i);
            if (Collections.frequency(received_tiebreakers, current_tiebreaker) > 1) {
                toRemove.add(negotiatedPackets.get(i));
            }
        }

        negotiatedPackets.removeAll(toRemove);

        for (SmallPacket negotiation_packet : negotiatedPackets) {
            int new_ip = routing.highest_assigned_ip + 1;
            int received_tiebreaker = (negotiation_packet.ackNum & 0b0011111);

            SmallPacket promotionPacket = new SmallPacket(routing.sourceIP, new_ip, received_tiebreaker | (hops << 5), true, false, true, false, true);
            packetHandling.sendSmallPacket(promotionPacket);
            routing.highest_assigned_ip = new_ip;
            routing.updateNeighbors(new_ip);
            sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT);
        }
        negotiatedPackets.clear();

        int unicast_scheme = routing.getUnicastScheme(routing.sourceIP);
        SmallPacket final_packet = new SmallPacket(routing.sourceIP, hops, unicast_scheme, true, false, true, true, true);
        packetHandling.sendSmallPacket(final_packet);
        sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT);

        startRequestMasterPhase();
    }

    private void startPostNegotiationSlavePhase(SmallPacket packet) throws InterruptedException {
        setState(State.POST_NEGOTIATION_SLAVE);
        int hops = (packet.ackNum & 0b1100000) >> 5;
        int multicastSchemeNumber = packet.ackNum & 0b0011111;

        routing.postNegotiationSlaveforwardingScheme = routing.getMulticastForwardingRouteFromOrder(packet.sourceIP, multicastSchemeNumber);

        if (hops == 0) routing.updateNeighbors(packet.sourceIP);


        if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
            // You have to forward this time
            packet.ackNum += (1 << 5);
            packetHandling.sendSmallPacket(packet);
        }

    }

    private void startPostNegotiationStrangerPhase(SmallPacket packet) {
        setState(State.POST_NEGOTIATION_STRANGER);
        // maybe do things here..? but we don't have to forward anything
        int hops = (packet.ackNum & 0b1100000) >> 5;
        int multicastSchemeNumber = packet.ackNum & 0b0011111;
        routing.shortTopology = new boolean[6];
        routing.longTopology = new boolean[4][4];
        routing.postNegotiationSlaveforwardingScheme = routing.getMulticastForwardingRouteFromOrder(packet.sourceIP, multicastSchemeNumber);
    }

    public void startRequestMasterPhase() {
        setState(State.REQUEST_MASTER);
        requestPackets.clear();
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        all_ips.remove(currentMaster);
        int request_phase_length = 0;

        for (int i = routing.sourceIP; i < all_ips.size(); i++) {
            if (all_ips.get(i) <= routing.highest_assigned_ip) {
                request_phase_length += 1; // Timeslot that a node sends.
                request_phase_length += routing.unicastRoutes.get(i).size();
            }

        }

        Timer timer = new Timer(request_phase_length * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
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
        setState(State.REQUEST_SLAVE);
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
        all_ips.remove(currentMaster);
        int thisNodesSendTurn = all_ips.get(routing.sourceIP);
        int thisNodesSendTimeslot = thisNodesSendTurn;

        for (int i = 0; i < thisNodesSendTurn; i++) {
            thisNodesSendTimeslot += routing.unicastRoutes.get(i).size();
        }

        Timer timer = new Timer(thisNodesSendTimeslot * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                try {
                    requestSlavePhaseSecondPart();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go g


    }

    public void requestSlavePhaseSecondPart() throws InterruptedException {
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
        setState(State.POST_REQUEST_MASTER);
        int how_many_timeslots_do_we_want = howManyTimeslotsDoWeWant();
        List<Integer> topologyNumbers = new ArrayList<>();

        timeslotsRequested = new ArrayList<>();
        for (SmallPacket packet : requestPackets) {
            int timeslots = (packet.destIP << 2) | ((packet.ackFlag ? 1 : 0) << 1) | (packet.SYN ? 1 : 0);
            timeslotsRequested.add(timeslots);
            int topologyNumber = packet.ackNum & 0b111111;
            topologyNumbers.add(topologyNumber);
        }

        topologyNumbers.add(routing.sourceIP, routing.getLinkTopologyBits());

        timeslotsRequested.add(routing.sourceIP, how_many_timeslots_do_we_want); // Adding our own request for timeslots
        while (timeslotsRequested.size() < 4) {
            timeslotsRequested.add(0);
        }
        int hops = 0;

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

        List<Integer> route_ips = routing.getMulticastForwardingRoute(routing.sourceIP);

        packetHandling.sendSmallPacket(first_packet);
        sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT);
        packetHandling.sendSmallPacket(second_packet);
        sleep(route_ips.size() * packetHandling.SHORT_PACKET_TIMESLOT);

        requestPackets.clear();

        startDataPhase();
    }

    private void startPostRequestSlavePhase(SmallPacket packet) {
        setState(State.POST_REQUEST_SLAVE);
        forwardedPackets.clear();

        timeslotsRequested = new ArrayList<>(Arrays.asList(-1, -1, -1, -1));

        int hops = packet.sourceIP;

        int received_topology = packet.ackNum & 0b0111111;
        int first_person_timeslots = ((packet.ackFlag ? 1 : 0) << 3) | ((packet.SYN ? 1 : 0) << 2) | packet.destIP;
        int second_person_first_bit = (packet.ackNum & 0b1000000) >> 3;
        routing.saveTopology(received_topology);
        timeslotsRequested.set(0, first_person_timeslots);
        timeslotsRequested.set(1, second_person_first_bit);

        if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
            // You have to forward this time
            // Source IP is not included here. No proper forwarding possible.
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

        final int finalDelay_after_we_send = delay_after_we_send;
        Timer timer = new Timer((delay_until_we_send * packetHandling.LONG_PACKET_TIMESLOT), new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                try {
                    dataPhaseSecondPart(finalDelay_after_we_send);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go g

    }

    private void dataPhaseSecondPart(int delay_after_we_send) throws InterruptedException {

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
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go g

    }

    public void dataPhaseThirdPart() throws InterruptedException {
        int next_master = (currentMaster + 1) % (routing.highest_assigned_ip + 1);
        if (next_master == routing.sourceIP) {
            startTimingMasterPhase(0);
        } else {
            startTimingSlavePhase(0, next_master);
        }
    }

    public void printByteBuffer(byte[] bytes, boolean buffered) {
        if (bytes!=null) {
            for (int i = 0; i < bytes.length; i++) {
                if (!buffered && i % 28 == 0) {
                    System.out.print("\n");
                    System.out.print("HEADER: ");
                }
                if (!buffered && i % 28 == 4) {
                    System.out.print("\n");
                    System.out.print("PAYLOAD: ");
                }
                byte aByte = bytes[i];
                System.out.print((char) aByte);
            }
            System.out.println();
            System.out.print("HEX: ");
            for (byte aByte : bytes) {
                System.out.print(String.format("%02x", aByte) + " ");
            }
            System.out.println();
        }
    }

    private void processMessage(ByteBuffer data, MessageType type) {
        byte[] bytes = new byte[0];
        if (data != null) {
            bytes = data.array();
        }
        switch (type) {
            case FREE:
                long delay = System.currentTimeMillis() - timeMilli;
                System.out.println("FREE. Delay since previous: " + delay);
                detectInterference(delay);
                return;
            case BUSY:
                System.out.println("BUSY");
                timeMilli = System.currentTimeMillis();
                return;
        }
        switch (state) {
            case DISCOVERY:
                if (type == MessageType.DATA_SHORT) {
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);

                    if (packet.broadcast && packet.negotiate && packet.request && !packet.ackFlag) { // another discovery packet
                        timer.stop();
                        exponentialBackoff *= 2;
                        startDiscoveryPhase(exponentialBackoff);
                    } else if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) { // timing master packet
                        timer.stop();
                        startTimingStrangerPhase(packet.ackNum);
                    }
                }
                break;
            case SENT_DISCOVERY:
                if (type == MessageType.DATA_SHORT) {
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    boolean other_discovery_packet = packet.broadcast && packet.negotiate && packet.request && !packet.ackFlag;
                    boolean discovery_denied_packet = packet.broadcast && packet.negotiate && packet.request && packet.ackFlag;

                    if ((other_discovery_packet && (tiebreaker <= packet.ackNum)) || (discovery_denied_packet && packet.ackNum == tiebreaker)) {
                        timer.stop();
                        exponentialBackoff *= 2;
                        startDiscoveryPhase(exponentialBackoff);
                    }

                    if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) {
                        timer.stop();
                        startTimingStrangerPhase(packet.ackNum);
                    }
                }
                break;
            case TIMING_MASTER:
                // is eigenlijk zo kort dat er niks kan gebeuren
                break;
            case TIMING_STRANGER:
                // is zo kort dat er niks kan gebeuren
                break;
            case TIMING_SLAVE:
                if (type == MessageType.DATA_SHORT) {
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
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
                if (type == MessageType.DATA_SHORT) {
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && !packet.ackFlag && !packet.request) {
                        negotiatedPackets.add(packet);
                    }
                }

                break;
            case NEGOTIATION_STRANGER:
//                switch (type) {
//                    case DATA_SHORT:
//                        System.out.println("DATA_SHORT");
//                        printByteBuffer(bytes, false); //Just print the data
//                        SmallPacket packet = packetHandling.readSmallPacket(bytes);
//                        break;
//                }

                break;

            case WAITING_FOR_TIMING_STRANGER:
                if (type == MessageType.DATA_SHORT) {
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) {
                        timer.stop();
                        startTimingStrangerPhase(packet.ackNum);
                    }
                }
                break;
            case NEGOTIATION_STRANGER_DONE:
                if (type == MessageType.DATA_SHORT) {// wait for POST_NEGOTIATION packet, if we got it, go to POST_NEGOTIATION_STRANGER
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
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
                // we shouldn't receive anything here?
                break;
            case POST_NEGOTIATION_SLAVE:
                if (type == MessageType.DATA_SHORT) {// wait for POST_NEGOTIATION packet, if we got it, go to POST_NEGOTIATION_STRANGER
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                        if (!packet.SYN && packet.sourceIP != packet.destIP) {
                            routing.highest_assigned_ip = packet.destIP;
                            int hops = packet.ackNum >> 5;
                            if (hops == 0) routing.updateNeighbors(packet.sourceIP);
                            if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
                                // TODO maybe use forwardedpackets
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
                if (type == MessageType.DATA_SHORT) {// wait for POST_NEGOTIATION packet, if we got it, go to POST_NEGOTIATION_STRANGER
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                        if (!packet.SYN && packet.sourceIP != packet.destIP) {
                            if (routing.sourceIP != -1) {
                                routing.highest_assigned_ip = packet.destIP;
                            }
                            if ((packet.ackNum & 0b0011111) == tiebreaker) {
                                routing.sourceIP = packet.destIP;
                                routing.highest_assigned_ip = packet.destIP;
                                currentMaster = packet.sourceIP;
                                routing.updateNeighbors(currentMaster);
                            }

                            int hops = packet.ackNum >> 5;
                            if (hops == 0) routing.updateNeighbors(packet.sourceIP);
                        } else if (packet.SYN) {
                            if (routing.sourceIP != -1) {
                                finalPostNegotiationHandler(packet);
                            } else {
                                // Finished POST_NEGOTIATION_STRANGER without an IP. This means we lost the tiebreaker.
                                timer.stop();
                                startWaitingForTimingStrangerPhase();
                            }
                        }
                    }
                }
                break;
            case REQUEST_MASTER:
                if (type == MessageType.DATA_SHORT) {
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (!packet.broadcast && !packet.negotiate && packet.request) {
                        requestPackets.add(packet);
                    }
                }
                break;
            case REQUEST_SLAVE:
                if (type == MessageType.DATA_SHORT) {
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
                    all_ips.remove(currentMaster);
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
                    if (!packet.negotiate && packet.request && packet.broadcast) {
                        timer.stop();
                        startPostRequestSlavePhase(packet);
                    }
                }
                break;
            case POST_REQUEST_MASTER:
                // do we even expect any data here..?
                break;
            case POST_REQUEST_SLAVE:
                if (type == MessageType.DATA_SHORT) {
                    System.out.println("DATA_SHORT");
                    printByteBuffer(bytes, false); //Just print the data
                    SmallPacket packet = packetHandling.readSmallPacket(bytes);
                    if (!packet.negotiate && packet.request && packet.broadcast) {
                        int second_person_requested_timeslot = timeslotsRequested.get(1) | ((packet.ackFlag ? 1 : 0) << 1) | packet.destIP;
                        int third_person_requested_timeslot = ((packet.SYN ? 1 : 0) << 7) | ((packet.ackNum & 0b01110000) >> 4);
                        int fourth_person_requested_timeslot = packet.ackNum & 0b1111;
                        timeslotsRequested.set(1, second_person_requested_timeslot);
                        timeslotsRequested.set(2, third_person_requested_timeslot);
                        timeslotsRequested.set(3, fourth_person_requested_timeslot);

                        int hops = packet.sourceIP;
                        try {
                            if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {

                                // You have to forward this time
                                packet.sourceIP += 1;
                                packetHandling.sendSmallPacket(packet);
                                Timer timer = new Timer((routing.postNegotiationSlaveforwardingScheme.size() - hops - 1) * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                                    @Override
                                    public void actionPerformed(ActionEvent arg0) {
                                        startDataPhase();
                                    }
                                });
                                timer.setRepeats(false); // Only execute once
                                timer.start(); // Go go g

                            } else {
                                Timer timer = new Timer((routing.postNegotiationSlaveforwardingScheme.size() - hops) * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                                    @Override
                                    public void actionPerformed(ActionEvent arg0) {
                                        startDataPhase();
                                    }
                                });
                                timer.setRepeats(false); // Only execute once
                                timer.start(); // Go go g
                            }
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }


                break;
            case DATA_PHASE:
                switch (type) {
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket smallPacket = packetHandling.readSmallPacket(bytes);
                        if (smallPacket.broadcast) {
                            throw new RuntimeException("Small packets cannot be multicast because they lack a hops field");
                        }
                        // TODO packets should not have request,negotiation flags etc. make error handler method?
                        if (smallPacket.destIP == routing.sourceIP) {
                            handleSmallPacket(smallPacket);
                        } else if (!smallPacket.broadcast && routing.getUnicastForwardingRoute(smallPacket.sourceIP, smallPacket.destIP).contains(routing.sourceIP)) {
                            // we are on the route
                            if (!forwardedPackets.contains(smallPacket)) {
                                forwardedPackets.add(smallPacket);
                                try {
                                    packetHandling.sendSmallPacket(smallPacket);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
//                        else if (smallPacket.broadcast && routing.getMulticastForwardingRoute(smallPacket.sourceIP).contains(sourceIP)) { // TODO dit kan niet
//                            if (!forwardedPackets.contains(smallPacket)){
//                                forwardedPackets.add(smallPacket);
//                                try {
//                                    packetHandling.sendSmallPacket(smallPacket);
//                                } catch (InterruptedException e) {
//                                    e.printStackTrace();
//                                }
//                            }
//                        }
                        break;
                    case DATA:
                        System.out.println("DATA");
                        printByteBuffer(bytes, false); //Just print the data
                        BigPacket bigPacket = packetHandling.readBigPacket(bytes);
                        if (bigPacket.hops == 0) routing.updateNeighbors(bigPacket.sourceIP);
                        // TODO maybe use forwardedpackets again
                        // Forwarding (you might have to forward multicast packet even if you handle it)
                        if (!bigPacket.broadcast && routing.getUnicastForwardingRoute(bigPacket.sourceIP, bigPacket.destIP).contains(routing.sourceIP)) {
                            // we are on the route
                            if (bigPacket.hops == routing.getUnicastForwardingRoute(bigPacket.sourceIP, bigPacket.destIP).indexOf(routing.sourceIP)) {
                                forwardedPackets.add(bigPacket);
                                try {
                                    bigPacket.hops += 1;
                                    packetHandling.sendPacket(bigPacket);
                                    bigPacket.hops -= 1;
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else if (bigPacket.broadcast && routing.getMulticastForwardingRoute(bigPacket.sourceIP).contains(routing.sourceIP)) {
                            if (bigPacket.hops == routing.getMulticastForwardingRoute(bigPacket.sourceIP).indexOf(routing.sourceIP)) {
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
            case READY:
                switch (type) {

                    case DATA:
                        System.out.println("DATA");
                        BigPacket packet = packetHandling.readBigPacket(bytes);

                        if (packet.morePackFlag || packetHandling.splitPacketBuffer.size() > 0) {
                            byte[] result = packetHandling.appendToBuffer(packet);
                            if (result.length > 0) {
                                printByteBuffer(result, true);
                            }
                        } else {
                            printByteBuffer(bytes, false); //Just print the data
                        }

                        System.out.println("source IP from this packet is" + packet.sourceIP);
                        System.out.println("packet sent to " + packet.destIP);

                        // TODO @Martijn sliding window protocol receiving side

                        // TODO stuur ACKs
                        // TODO last (cumulative) ack sent bijhouden als field
                        // TODO last packet received bijhouden als field
                        // TODO LAS + RWS = einde van je window, bijhouden als field
                        // TODO lijst aan booleans met welke packets je al binnen hebt binnen je window


                        break;
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
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
                }
        }

    }

    private void handleBigPacket(BigPacket bigPacket) {

    }

    private void handleSmallPacket(SmallPacket smallPacket) {

    }

    private void finalPostNegotiationHandler(SmallPacket packet) {
        // Handles slave/stranger side of final post negotiation phase packet
        if (packet.SYN) {
            List<Integer> all_ips = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
            all_ips.remove(packet.sourceIP);
            currentMaster = packet.sourceIP; // TODO set this somewhere else where it makes more sense?
            // 124 DEC is 444 PENT
            int[] route_numbers = new int[]{(packet.ackNum / 25) % 5, (packet.ackNum / 5) % 5, packet.ackNum % 5};

            routing.unicastRoutes = new ArrayList<>();
            for (int i = 0; i < route_numbers.length; i++) {

                List<Integer> ip_list = new ArrayList<>(Arrays.asList(0, 1, 2, 3));
                ip_list.remove(packet.sourceIP);

                ip_list.remove(i); // this will remove by index, not element
                int order = route_numbers[i];
                List<Integer> unicastRoute = Mathematics.decodePermutationOfTwo(order, ip_list);
                routing.unicastRoutes.add(unicastRoute);
            }
            routing.unicastRouteToMaster = routing.unicastRoutes.get(all_ips.indexOf(routing.sourceIP));

            int hops = packet.destIP;
            try {
                if (routing.postNegotiationSlaveforwardingScheme.size() - 1 >= hops && routing.postNegotiationSlaveforwardingScheme.get(hops) == routing.sourceIP) {
                    // You have to forward this time
                    packet.destIP += 1;
                    packetHandling.sendSmallPacket(packet);
                    Timer timer = new Timer((routing.postNegotiationSlaveforwardingScheme.size() - hops - 1) * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent arg0) {
                            startRequestSlavePhase();
                        }
                    });
                    timer.setRepeats(false); // Only execute once
                    timer.start(); // Go go g

                } else {
                    Timer timer = new Timer((routing.postNegotiationSlaveforwardingScheme.size() - hops) * packetHandling.SHORT_PACKET_TIMESLOT, new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent arg0) {
                            startRequestSlavePhase();
                        }
                    });
                    timer.setRepeats(false); // Only execute once
                    timer.start(); // Go go g
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }


        }
    }

    void detectInterference(long delay) {
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
                        exponentialBackoff *= 2;
                        startDiscoveryPhase(exponentialBackoff);
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
                }

            } else {
                if (state == State.NEGOTIATION_STRANGER) { // TODO possibly incorrect if
                    timer.stop();
                    startNegotiationStrangerDonePhase();
                }

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

