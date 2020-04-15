import client.*;
import com.sun.deploy.net.MessageHeader;

import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
* This is just some example code to show you how to interact 
* with the server using the provided client and two queues.
* Feel free to modify this code in any way you like!
*/


public class MyProtocol{
    private int tiebreaker;
    private Date date = new Date();
    private long timeMilli;
    private int negotiation_phase_length = 8;
    public int SHORT_PACKET_TIMESLOT = 251;
    public int LONG_PACKET_TIMESLOT = 1500;
    private int negotiation_phase_stranger_length;
    private int negotiation_phases_encountered = 0;
    private List<SmallPacket> negotiatedPackets = new ArrayList<>();
    int highest_assigned_ip = -1;
    private List<Integer> postNegotiationSlaveforwardingScheme;
    private List<Integer> unicastRouteToMaster;
    List<List<Integer>> unicastRoutes;
    private int current_master;
    private List<SmallPacket> requestPackets = new ArrayList<>();
    private List<SmallPacket> forwardedPackets = new ArrayList<>();


    public enum State{
        NULL,
        DISCOVERY,
        SENT_DISCOVERY,

        // etc
        NEGOTIATION_MASTER,
        READY,
        TIMING_SLAVE,
        TIMING_MASTER, TIMING_STRANGER, NEGOTIATION_STRANGER, POST_NEGOTIATION_MASTER, WAITING_FOR_TIMING_STRANGER, NEGOTIATION_STRANGER_DONE, POST_NEGOTIATION_SLAVE, POST_NEGOTIATION_STRANGER, REQUEST_SLAVE, REQUEST_MASTER, POST_REQUEST_MASTER, POST_REQUEST_SLAVE;
    }

    public class SmallPacket{
        // First byte
        int sourceIP; // 2 bit number, range [0,3]
        int destIP; // 2 bit number, range [0,3]
        boolean ackFlag;
        boolean request;
        boolean broadcast;
        boolean SYN;

        // Second byte
        boolean negotiate;
        int ackNum; // 7 bit number, range [0,127]

        public SmallPacket(){

        }

        public SmallPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast) {
            this.sourceIP = sourceIP;
            this.destIP = destIP;
            this.ackNum = ackNum;
            this.ackFlag = ackFlag;
            this.request = request;
            this.negotiate = negotiate;
            this.SYN = SYN;
            this.broadcast = broadcast;
        }
    }

    public class BigPacket extends SmallPacket{
        // Third byte
        boolean morePackFlag;
        int seqNum; // 7 bit number, range [0,127]

        // Fourth byte
        int size; // 5 bit number, range [0,31]

        // Other bytes
        byte[] payload;
        byte[] payloadWithoutPadding;

        public BigPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast, byte[] payloadWithoutPadding, int seqNum, boolean morePackFlag, int size) {
            super(sourceIP, destIP, ackNum, ackFlag, request, negotiate, SYN, broadcast);
            this.payload = new byte[28];
            for (int j = 0; j < size-4; j++) {
                payload[j] = payloadWithoutPadding[j];
            }
            this.payloadWithoutPadding = payloadWithoutPadding;
            this.seqNum = seqNum;
            this.morePackFlag = morePackFlag;
            this.size = size;
        }

        public BigPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast, byte[] payload, byte[] payloadWithoutPadding, int seqNum, boolean morePackFlag, int size) {
            super(sourceIP, destIP, ackNum, ackFlag, request, negotiate, SYN, broadcast);
            this.payload = payload;
            this.payloadWithoutPadding = payloadWithoutPadding;
            this.seqNum = seqNum;
            this.morePackFlag = morePackFlag;
            this.size = size;
        }
    }
    // The host to connect to. Set this to localhost when using the audio interface tool.
    private static String SERVER_IP = "netsys2.ewi.utwente.nl"; //"127.0.0.1";
    // The port to connect to. 8954 for the simulation server.
    private static int SERVER_PORT = 8954;
    // The frequency to use.
    private static int frequency = 5400;
    private int exponential_backoff = 1;

    public void setState(State state) {
        this.state = state;
        System.out.println("NEW STATE: " +state.toString());
    }

    private State state = State.READY;

    private BlockingQueue<Message> receivedQueue;
    private BlockingQueue<Message> sendingQueue;

    private boolean sending = false;
    private List<Message> messagesToSend = new ArrayList<>();
    private List<Message> sentMessages = new ArrayList<>(); // TODO might overflow


    Timer timer;
    List<Byte> buffer = new ArrayList<>();
    int sourceIP = -1;

    public MyProtocol(String server_ip, int server_port, int frequency, int sourceIP){
        receivedQueue = new LinkedBlockingQueue<Message>();
        sendingQueue = new LinkedBlockingQueue<Message>();


        new Client(SERVER_IP, SERVER_PORT, frequency, receivedQueue, sendingQueue); // Give the client the Queues to use

        new receiveThread(receivedQueue).start(); // Start thread to handle received messages!



        // handle sending from stdin from this thread.
        try{
            ByteBuffer temp = ByteBuffer.allocate(1024);
            int read = 0;
            while(true){
                read = System.in.read(temp.array()); // Get data from stdin, hit enter to send!
                System.out.println(read-1);

                if (state==State.NULL) { // TODO actually use states here
                    System.out.println("Can't send messages while negotiating");
                } else if(read > 0){
                    ByteBuffer text = ByteBuffer.allocate(read-1); // jave includes newlines in System.in.read, so -2 to ignore this
                    text.put( temp.array(), 0, read-1 ); // java includes newlines in System.in.read, so -2 to ignore this
                    String textString = new String(text.array(), StandardCharsets.US_ASCII);
                    if (textString.equals("DISCOVERY")){
                        startDiscoveryPhase(exponential_backoff);
                    } else if (textString.equals("DISCOVERYNOW")) {
                        startDiscoveryPhase(0);
                    } else if (textString.equals("SMALLPACKET")) {
                        sendSmallPacket(new SmallPacket(0,0,0,false,false,false,false,false));
                    } else {
                        for (int i = 0; i < read-1; i+=28) {
                            byte[] partial_text = read-1-i>28? new byte[28] : new byte[read-1-i];
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


                            boolean morePacketsFlag = read-1-i>28;
                            int size = morePacketsFlag? 32 : read-1-i+4;
                            sendPacket(new BigPacket(sourceIP,0,0,false,false,false,false,true,partial_text,0,morePacketsFlag,size));

                        }
                    }
                }
            }

        } catch (InterruptedException e){
            System.exit(2);
        } catch (IOException e){
            System.exit(2);
        }        
    }

    private void startDiscoveryPhase(int exponential_backoff) {
        setState(State.DISCOVERY);
        highest_assigned_ip = -1;
        sourceIP = -1;
        tiebreaker = new Random().nextInt(1<<7);
        timer = new Timer(new Random().nextInt(2000*exponential_backoff + 1)+8000*exponential_backoff, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state==State.DISCOVERY) {
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

    public void sendPacket(BigPacket packet) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(32); // jave includes newlines in System.in.read, so -2 to ignore this
        byte[] packetBytes = fillBigPacket(packet);
        toSend.put(packetBytes, 0, 32); // jave includes newlines in System.in.read, so -2 to ignore this
        Message msg = new Message(MessageType.DATA, toSend);
        sending = true;
        messagesToSend.add(msg);
        sendingQueue.put(msg);
    }

    public void sendSmallPacket(SmallPacket packet) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(2);
        byte[] packetBytes = fillSmallPacket(packet);
        toSend.put( packetBytes, 0, 2); // jave includes newlines in System.in.read, so -2 to ignore this
        Message msg = new Message(MessageType.DATA_SHORT, toSend);
        sending = true;
        messagesToSend.add(msg);
        sendingQueue.put(msg);
    }

    private void startSentDiscoveryPhase() throws InterruptedException {
        SmallPacket packet = new SmallPacket(0,0,tiebreaker,false,true,true,false,true);
        sendSmallPacket(packet);
        setState(State.SENT_DISCOVERY);
        timer = new Timer(2000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state==State.SENT_DISCOVERY) {
                    try {
                        sourceIP = 0;
                        highest_assigned_ip = 0;
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
        current_master = sourceIP;
        setState(State.TIMING_MASTER);
        if (new_negotiation_phase_length > 0) {
            this.negotiation_phase_length = new_negotiation_phase_length;
        }
        exponential_backoff = 1;
        if (new_negotiation_phase_length == 0 && this.negotiation_phase_length > 1) { // If we are using an old phase length number that is above 1
            this.negotiation_phase_length /= 2;
        }

        SmallPacket packet = new SmallPacket(sourceIP,0,this.negotiation_phase_length,false,false,false,true,true);
        sendSmallPacket(packet);
        startNegotiationMasterPhase();
    }

    private void startTimingSlavePhase(int new_negotiation_phase_length, int new_master_ip) {
        if (new_negotiation_phase_length == 0 && this.negotiation_phase_length > 1) { // If we are using an old phase length number that is above 1
            this.negotiation_phase_length /= 2;
        }
        setState(State.TIMING_SLAVE);
        exponential_backoff = 1;
        timer = new Timer(10000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) { // TODO welke delay
                if (state==State.TIMING_SLAVE) {
                    exponential_backoff = 1;
                    startDiscoveryPhase(exponential_backoff);

                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!

    }

    private void startTimingStrangerPhase(int negotiation_length) {
        setState(State.TIMING_STRANGER);
        exponential_backoff = 1;
        this.negotiation_phase_stranger_length = negotiation_length;
        try {
            startNegotiationStrangerPhase();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private void startWaitingForTimingStrangerPhase() {
        setState(State.WAITING_FOR_TIMING_STRANGER);
        exponential_backoff = 1;
        timer = new Timer(20000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) { // TODO welke delay
                if (state==State.WAITING_FOR_TIMING_STRANGER) {
                    exponential_backoff = 1;
                    startDiscoveryPhase(exponential_backoff);
                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!

    }

    private void startNegotiationMasterPhase() throws InterruptedException {
        setState(State.NEGOTIATION_MASTER);
        negotiatedPackets.clear();
        wait(this.negotiation_phase_length*SHORT_PACKET_TIMESLOT);
        startPostNegotiationMasterPhase();
    }

    private void startNegotiationStrangerPhase() throws InterruptedException {
        setState(State.NEGOTIATION_STRANGER);
        this.negotiation_phases_encountered++;
        for (int i = 0; i < this.negotiation_phase_stranger_length; i++) {
            if (state!=State.NEGOTIATION_STRANGER) {
                return;
            }
            float roll = new Random().nextFloat() *(1+ ((float)this.negotiation_phases_encountered-1)/10);
            if (roll > 0.25) {
                tiebreaker = new Random().nextInt(1 << 5);
                SmallPacket packet = new SmallPacket(0,0,tiebreaker,false,false,true,false,true);
                sendSmallPacket(packet);
            } else {
                wait(SHORT_PACKET_TIMESLOT);
            }
        }
        startWaitingForTimingStrangerPhase();

    }



    private void startNegotiationStrangerDonePhase() {
        setState(State.NEGOTIATION_STRANGER_DONE);
        timer = new Timer(5000, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if (state==State.NEGOTIATION_STRANGER_DONE) {
                    startWaitingForTimingStrangerPhase();
                }
            }
        });
        timer.setRepeats(false); // Only execute once
        timer.start(); // Go go go!
    }

    private List<Integer> getMulticastForwardingRoute() {
        // TODO @Freek implement
        return new ArrayList<>();
    }

    private List<Integer> getUnicastForwardingRoute(int firstIP, int secondIP) {
        // TODO @Freek implement
        return new ArrayList<>();
    }

    private int getLinkTopologyBits() {
        // TODO @Freek implement
        return 0;
    }

    private int getMulticastForwardingRouteNumber(int ip, List<Integer> route_ips) {
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
        all_ips.remove(ip);

        int first_hop = route_ips.size() > 0? route_ips.get(0) : -1;
        int second_hop = route_ips.size() > 1? route_ips.get(1) : -1;

        int first_hop_index = all_ips.indexOf(first_hop);
        int second_hop_index = all_ips.indexOf(second_hop);

        return encodePermutationOfThree(first_hop_index,second_hop_index);
    }

    public List<Integer> getMulticastForwardingRouteFromOrder(int ip, int order) {
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
        all_ips.remove(ip);
        return decodePermutationOfThree(order, all_ips);
    }

    private int getUnicastScheme(int sourceIP) {
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
        int[] unicast_route_number = new int[3];
        all_ips.remove(sourceIP);
        for (int i = 0; i < all_ips.size(); i++) {
            int destinationIP = all_ips.get(i);
            List<Integer> relevant_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
            relevant_ips.remove(sourceIP);
            relevant_ips.remove(destinationIP);

            List<Integer> unicastRoute = getUnicastForwardingRoute(destinationIP,sourceIP);
            int first_hop = unicastRoute.size() > 0? unicastRoute.get(0) : -1;
            int second_hop = unicastRoute.size() > 1? unicastRoute.get(1) : -1;

            int first_hop_index = relevant_ips.indexOf(first_hop);
            int second_hop_index = relevant_ips.indexOf(second_hop);

            unicast_route_number[i] = encodePermutationOfTwo(first_hop_index,second_hop_index);
        }
        return unicast_route_number[0]*5*5  + unicast_route_number[1]*5 + unicast_route_number[2];
    }



    private void startPostNegotiationMasterPhase() throws InterruptedException {
        setState(State.POST_NEGOTIATION_MASTER);

        List<Integer> route_ips = getMulticastForwardingRoute();
        int route = getMulticastForwardingRouteNumber(sourceIP,route_ips);

        int hops = 0;
        int first_packet_ack_nr = hops << 6 | route;
        SmallPacket first_packet = new SmallPacket(sourceIP, sourceIP, first_packet_ack_nr,true,false,true,false,true);
        sendSmallPacket(first_packet);

        wait(route_ips.size()*SHORT_PACKET_TIMESLOT);
        List<Integer> received_tiebreakers = new ArrayList<>();

        // Make list of all tiebreakers
        for (SmallPacket negotiation_packet: negotiatedPackets) {
            int received_tiebreaker = (negotiation_packet.ackNum & 0b0011111);
            received_tiebreakers.add(received_tiebreaker);
        }

        // If we find a packet that has a tie, add it to the list of packets to be removed
        List<SmallPacket> toRemove = new ArrayList<>();
        for (int i = 0; i < received_tiebreakers.size(); i++) {
            int current_tiebreaker = received_tiebreakers.get(i);
            if (Collections.frequency(received_tiebreakers,current_tiebreaker) > 1) {
                toRemove.add(negotiatedPackets.get(i));
            }
        }

        negotiatedPackets.removeAll(toRemove);

        for (SmallPacket negotiation_packet: negotiatedPackets) {
            int new_ip = highest_assigned_ip + 1;
            int received_tiebreaker = (negotiation_packet.ackNum & 0b0011111);
            SmallPacket promotionPacket = new SmallPacket(sourceIP, new_ip, received_tiebreaker | (hops << 5),true,false,true,false,true);
                sendSmallPacket(promotionPacket);
                highest_assigned_ip++;
                wait(route_ips.size()*SHORT_PACKET_TIMESLOT);
        }
        negotiatedPackets.clear();

        int unicast_scheme = getUnicastScheme(sourceIP);
        SmallPacket final_packet = new SmallPacket(sourceIP,hops,unicast_scheme,true,false,true,true,true);
        sendSmallPacket(final_packet);
        wait(route_ips.size()*SHORT_PACKET_TIMESLOT);

        startRequestMasterPhase();
    }




    private void startPostNegotiationSlavePhase(SmallPacket packet) throws InterruptedException {
        setState(State.POST_NEGOTIATION_SLAVE);
        int hops = (packet.ackNum & 0b1100000) >> 5;
        int multicastSchemeNumber = packet.ackNum & 0b0011111;

        postNegotiationSlaveforwardingScheme = getMulticastForwardingRouteFromOrder(packet.sourceIP,multicastSchemeNumber);

        if (postNegotiationSlaveforwardingScheme.get(hops) == sourceIP) {
            // You have to forward this time
            packet.ackNum += (1 << 5);
            sendSmallPacket(packet);
        }

    }

    private void startPostNegotiationStrangerPhase(SmallPacket packet) {
        setState(State.POST_NEGOTIATION_STRANGER);
        // maybe do things here..? but we don't have to forward anything
        int hops = (packet.ackNum & 0b1100000) >> 5;
        int multicastSchemeNumber = packet.ackNum & 0b0011111;

        postNegotiationSlaveforwardingScheme = getMulticastForwardingRouteFromOrder(packet.sourceIP,multicastSchemeNumber);
    }

    public void startRequestMasterPhase() throws InterruptedException {
        setState(State.REQUEST_MASTER);
        requestPackets.clear();
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
        all_ips.remove(current_master);
        int request_phase_length = 0;

        for (int i = sourceIP; i < all_ips.size(); i++) {
            if (all_ips.get(i)<=highest_assigned_ip) {
                request_phase_length += 1; // Timeslot that a node sends.
                request_phase_length += unicastRoutes.get(i).size();
            }

        }


        wait(request_phase_length*SHORT_PACKET_TIMESLOT);
        startPostRequestMasterPhase();
    }

    public void startRequestSlavePhase() throws InterruptedException {
        setState(State.REQUEST_SLAVE);
        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
        all_ips.remove(current_master);
        int thisNodesSendTurn = all_ips.get(sourceIP);
        int thisNodesSendTimeslot = thisNodesSendTurn;

        for (int i = 0; i < thisNodesSendTurn; i++) {
            thisNodesSendTimeslot += unicastRoutes.get(i).size();
        }

        wait(thisNodesSendTimeslot*SHORT_PACKET_TIMESLOT);

        int how_many_timeslots_do_we_want = 4; // TODO integrate with Martijn to figure out how many timeslots we want
        int bit_1_and_2 = (how_many_timeslots_do_we_want & 0b1100) >> 2;
        boolean bit_3 = ((how_many_timeslots_do_we_want & 0b0010) >> 1) == 1;
        boolean bit_4 = (how_many_timeslots_do_we_want & 0b0001) == 1;

        int link_topology_bits = getLinkTopologyBits();

        SmallPacket packet = new SmallPacket(sourceIP,bit_1_and_2, link_topology_bits,bit_3,true,false,bit_4,false);
        sendSmallPacket(packet);

//        int delay_until_post_request_phase = 0;
//
//        for (int i = sourceIP; i < all_ips.size(); i++) {
//            if (all_ips.get(i)<=highest_assigned_ip) {
//                if (i>sourceIP) {
//                    delay_until_post_request_phase += 1; // Timeslot that a node sends.
//                }
//                delay_until_post_request_phase += unicastRoutes.get(i).size();
//            }
//
//        }

    }

    private void startPostRequestMasterPhase() throws InterruptedException {
        setState(State.POST_REQUEST_MASTER);
        int how_many_timeslots_do_we_want = 4; // TODO integrate with Martijn to figure out how many timeslots we want

        List<Integer> timeslotsRequested = new ArrayList<>();
        for (SmallPacket packet: requestPackets) {
            int timeslots = (packet.destIP << 2) | ((packet.ackFlag?1:0) << 1) | (packet.SYN?1:0);
            timeslotsRequested.add(timeslots);
        }
        
        timeslotsRequested.add(sourceIP,how_many_timeslots_do_we_want); // Adding our own request for timeslots
        
        int packet1_bits_1_and_2 = (timeslotsRequested.get(0) & 0b1100) >> 2;
        int packet1_bits_3_and_4 = (timeslotsRequested.get(0) & 0b0011);
        int topology = 0; // TODO @Freek update topology based on all these packets + your own incomplete topology
        SmallPacket first_packet = new SmallPacket(packet1_bits_1_and_2,packet1_bits_3_and_4,topology,false,true,false,false,true);

        int packet2_bits_1_and_2 = (timeslotsRequested.get(1) & 0b1100) >> 2;
        int packet2_bits_3_and_4 = (timeslotsRequested.get(1) & 0b0011);

        boolean packet2_synflag = (timeslotsRequested.get(2) & 0b1000) >> 3 == 1;
        int packet2_acknum = ((timeslotsRequested.get(2) & 0b0111) << 4) | timeslotsRequested.get(3);

        SmallPacket second_packet = new SmallPacket(packet2_bits_1_and_2, packet2_bits_3_and_4,packet2_acknum,true,true,false,packet2_synflag,true);

        sendSmallPacket(first_packet);
        sendSmallPacket(second_packet);
        requestPackets.clear();

        // TODO @Freek change state when the time is right
        // TODO @Freek receiver side
    }

    private void startPostRequestSlavePhase(SmallPacket packet) {
        setState(State.POST_REQUEST_SLAVE);
        forwardedPackets.clear();

        // TODO might there be another packet coming? implement master first
        // TODO @Freek start of data phase depends on the "hop" counter in this packet.
        // TODO @Freek change state when time is right
    }

    public byte[] fillSmallPacket(SmallPacket packet) {
        byte first_byte = (byte) (packet.sourceIP << 6 | packet.destIP << 4 | (packet.ackFlag ? 1:0) << 3 | (packet.request ? 1:0) << 2 | (packet.broadcast ? 1:0) << 1 | (packet.SYN ? 1:0));
        byte second_byte = (byte) ((packet.negotiate? 1:0) << 7 | packet.ackNum);
        return new byte[]{first_byte, second_byte};
    }

    public byte[] fillBigPacket(BigPacket packet) {
        byte[] result = new byte[32];
        byte[] first_two_bytes = fillSmallPacket(packet);
        result[0] = first_two_bytes[0];
        result[1] = first_two_bytes[1];
        result[2] = (byte) ((packet.morePackFlag?1:0) << 7 | packet.seqNum);
        result[3] = (byte) (packet.size-1); // three bits left here
        for (int i = 4; i <= 31 ; i++) {
            result[i] = packet.payload[i-4];
        }
        return result;
    }

    public SmallPacket readSmallPacket(byte[] bytes) {
        SmallPacket packet = new SmallPacket();
        packet.sourceIP = (bytes[0] >> 6) & 0x03;
        packet.destIP = (bytes[0] >> 4) & 0x03;
        packet.ackFlag = ((bytes[0] >> 3) & 0x01)==1;
        packet.request = ((bytes[0] >> 2) & 0x01)==1;
        packet.broadcast = ((bytes[0] >> 1) & 0x01)==1;
        packet.SYN = (bytes[0]  & 0x01)==1;

        packet.negotiate = ((bytes[1] >> 7) & 0x01)==1;
        packet.ackNum = bytes[1] & 0x7F;

        return packet;
    }

    public BigPacket readBigPacket(byte[] bytes) {
        SmallPacket smallPacket = readSmallPacket(bytes);
        boolean morePackFlag = ((bytes[2] >> 7) & 0x01)==1;
        int seqNum = bytes[2] & 0x7F;
        int size = (bytes[3] & 0x1F) + 1;
        if (size>32){
            System.err.println("Packet size is too big");
        }
        byte[] payload = new byte[28];
        byte[] payloadWithoutPadding = new byte[size-4];

        for (int i = 4; i <= 31; i++) {
            payload[i-4] = bytes[i];
            if (i<size) {
                payloadWithoutPadding[i-4] = bytes[i];
            }
        }
        BigPacket packet = new BigPacket(smallPacket.sourceIP, smallPacket.destIP, smallPacket.ackNum, smallPacket.ackFlag, smallPacket.request, smallPacket.negotiate, smallPacket.SYN, smallPacket.broadcast, payload, payloadWithoutPadding, seqNum, morePackFlag, size);
        return packet;
    }

    public byte[] appendToBuffer(BigPacket packet) {
        // todo ewrite deze functie. je wilt duidelijk hele packets bufferen (zodat je later bijvoorbeeld kan kijken wat de laatste sequence nr is die je krijgt enzo)
        for (int i = 0; i < packet.payloadWithoutPadding.length; i++) {
            buffer.add(packet.payloadWithoutPadding[i]);
        }

        if (packet.morePackFlag) {
            return new byte[]{};
        } else {
            byte[] bufferCopy = new byte[buffer.size()];
            for (int i = 0; i < buffer.size(); i++) {
                bufferCopy[i] = buffer.get(i);
            }
            buffer.clear();
            return bufferCopy;
        }
    }

    public static void main(String args[]) {
        int sourceIP= -1;
        if(args.length > 0){
            //frequency = Integer.parseInt(args[0]);

            sourceIP = Integer.parseInt(args[0]);
            System.out.println("source IP is: " + sourceIP);
        }
         new MyProtocol(SERVER_IP, SERVER_PORT, frequency, sourceIP);
    }
    public void printByteBuffer(byte[] bytes, boolean buffered){
        for (int i = 0; i < bytes.length; i++) {
            if (!buffered && i%28 == 0) {
                System.out.print("\n");
                System.out.print("HEADER: ");
            }
            if (!buffered && i%28 == 4) {
                System.out.print("\n");
                System.out.print("PAYLOAD: ");
            }
            byte aByte = bytes[i];
            System.out.print((char) aByte);
        }
        System.out.println();
        System.out.print( "HEX: ");
        for (byte aByte : bytes) {
            System.out.print(String.format("%02x", aByte) + " ");
        }
        System.out.println();

    }

    private class receiveThread extends Thread {
        private BlockingQueue<Message> receivedQueue;

        public receiveThread(BlockingQueue<Message> receivedQueue){
            super();
            this.receivedQueue = receivedQueue;
        }

        public void run(){
            while(true) {
                try{
                    Message m = receivedQueue.take();
                    processMessage(m.getData(),m.getType());
                } catch (InterruptedException e){
                    System.err.println("Failed to take from queue: "+e);
                }
            }
        }
    }

    private void processMessage(ByteBuffer data, MessageType type) {
        byte [] bytes = null;
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
                switch (type) {
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);

                        if (packet.broadcast && packet.negotiate && packet.request && !packet.ackFlag) { // another discovery packet
                            timer.stop();
                            exponential_backoff*=2;
                            startDiscoveryPhase(exponential_backoff);
                        } else if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) { // timing master packet
                            timer.stop();
                            startTimingStrangerPhase(packet.ackNum);
                        }
                        break;
                }
                break;
            case SENT_DISCOVERY:
                switch (type) {
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
                        boolean other_discovery_packet = packet.broadcast && packet.negotiate && packet.request && !packet.ackFlag;
                        boolean discovery_denied_packet = packet.broadcast && packet.negotiate && packet.request && packet.ackFlag;

                        if ((other_discovery_packet && ( tiebreaker <= packet.ackNum) ) || ( discovery_denied_packet && packet.ackNum == tiebreaker)) {
                            timer.stop();
                            exponential_backoff*=2;
                            startDiscoveryPhase(exponential_backoff);
                        }

                        if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) {
                            timer.stop();
                            startTimingStrangerPhase(packet.ackNum);
                        }
                        break;
                }
                break;
            case TIMING_MASTER:
                // is eigenlijk zo kort dat er niks kan gebeuren
                break;
            case TIMING_STRANGER:
                // is zo kort dat er niks kan gebeuren
                break;
            case TIMING_SLAVE:
                switch (type) {
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
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
                        break;
                }
                break;
            case NEGOTIATION_MASTER:
                switch (type) {
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
                        if (packet.broadcast && packet.negotiate && !packet.ackFlag && !packet.request) {
                            negotiatedPackets.add(packet);
                        }
                        break;
                }

                break;
            case NEGOTIATION_STRANGER:
//                switch (type) {
//                    case DATA_SHORT:
//                        System.out.println("DATA_SHORT");
//                        printByteBuffer(bytes, false); //Just print the data
//                        SmallPacket packet = readSmallPacket(bytes);
//                        break;
//                }

                break;

            case WAITING_FOR_TIMING_STRANGER:
                switch (type) {
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
                        if (!packet.negotiate && !packet.request && packet.broadcast && packet.SYN) {
                            timer.stop();
                            startTimingStrangerPhase(packet.ackNum);
                        }
                        break;
                }
                break;
            case NEGOTIATION_STRANGER_DONE:
                switch (type) {
                    case DATA_SHORT:
                        // wait for POST_NEGOTIATION packet, if we got it, go to POST_NEGOTIATION_STRANGER
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
                        if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                            if (!packet.SYN && packet.sourceIP == packet.destIP) {
                                timer.stop();
                                startPostNegotiationStrangerPhase(packet);
                            }
                        }
                        break;
                }
                break;
            case POST_NEGOTIATION_MASTER:
                // we shouldn't receive anything here?
                break;
            case POST_NEGOTIATION_SLAVE:
                switch (type) {
                    case DATA_SHORT:
                        // wait for POST_NEGOTIATION packet, if we got it, go to POST_NEGOTIATION_STRANGER
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
                        if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                            if (!packet.SYN && packet.sourceIP != packet.destIP) {
                                highest_assigned_ip = packet.destIP;
                                int hops = packet.ackNum >> 5;
                                if (postNegotiationSlaveforwardingScheme.get(hops) == sourceIP) {
                                    // You have to forward this time
                                    // TODO maybe use forwardedPackets here? make sure to clear at start of next phase.
                                    packet.ackNum += (1 << 5);
                                    try {
                                        sendSmallPacket(packet);
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
                switch (type) {
                    case DATA_SHORT:
                        // wait for POST_NEGOTIATION packet, if we got it, go to POST_NEGOTIATION_STRANGER
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
                        if (packet.broadcast && packet.negotiate && packet.ackFlag && !packet.request) {
                            if (!packet.SYN && packet.sourceIP != packet.destIP) {
                                if (sourceIP != -1) {
                                    highest_assigned_ip = packet.destIP;
                                }
                                if ((packet.ackNum & 0b0011111 ) == tiebreaker) {
                                    sourceIP = packet.destIP;
                                    highest_assigned_ip = packet.destIP;
                                    current_master = packet.sourceIP;
                                }


                                int hops = packet.ackNum >> 5;
                                if (postNegotiationSlaveforwardingScheme.get(hops) == sourceIP) {
                                    // You have to forward this time
                                    // TODO maybe use forwardedPackets here? make sure to clear at start of next phase..
                                    packet.ackNum += (1 << 5);
                                    try {
                                        sendSmallPacket(packet);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                            } else if (packet.SYN) {
                                if (sourceIP != -1) {
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
                switch (type) {
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
                        if (!packet.broadcast && !packet.negotiate && packet.request) {
                            requestPackets.add(packet);
                        }
                        break;
                }
                break;
            case REQUEST_SLAVE:
                switch (type) {
                    case DATA_SHORT:
                        System.out.println("DATA_SHORT");
                        printByteBuffer(bytes, false); //Just print the data
                        SmallPacket packet = readSmallPacket(bytes);
                        List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
                        all_ips.remove(current_master);
                        if (!packet.broadcast && !packet.negotiate && packet.request) {
                            // If we are in the route of the person that sent this packet
                            if (unicastRoutes.get( all_ips.indexOf(packet.sourceIP)).contains(sourceIP) && !forwardedPackets.contains(packet)) { // TODO THIS MIGHT NOT WORK
                                try {
                                    sendSmallPacket(packet); // We have to forward it
                                    forwardedPackets.add(packet);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                        if (!packet.negotiate && packet.request && packet.broadcast) {
                            timer.stop();
                            startPostRequestSlavePhase(packet); // TODO Might not even be a small packet...
                        }
                        break;

                }
                break;
            case POST_REQUEST_MASTER:
                // TODO @freek
                break;
            case POST_REQUEST_SLAVE:
                // TODO @freek

                break;
            case READY:
                switch (type) {

                    case DATA:
                        System.out.println("DATA");
                        BigPacket packet = readBigPacket(bytes);

                        if (packet.morePackFlag || buffer.size() > 0) {
                            byte[] result = appendToBuffer(packet);
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



    private void finalPostNegotiationHandler(SmallPacket packet) {
        // Handles slave/stranger side of final post negotiation phase packet
        if (packet.SYN) {
            List<Integer> all_ips = new ArrayList<>(Arrays.asList(0,1,2,3));
            all_ips.remove(packet.sourceIP);
            current_master = packet.sourceIP; // TODO set this somewhere else where it makes more sense?
            // 124 DEC is 444 PENT
            int[] route_numbers = new int[]{(packet.ackNum / 25) % 5,(packet.ackNum / 5) % 5, packet.ackNum % 5};

            unicastRoutes = new ArrayList<>();
            for (int i = 0; i < route_numbers.length; i++) {

                List<Integer> ip_list = new ArrayList<>(Arrays.asList(0,1,2,3));
                ip_list.remove(packet.sourceIP);

                ip_list.remove(i); // this will remove by index, not element
                int order = route_numbers[i];
                List<Integer> unicastRoute = decodePermutationOfTwo(order, ip_list);
                unicastRoutes.set(i,unicastRoute);
            }
            unicastRouteToMaster = unicastRoutes.get(all_ips.indexOf(sourceIP));

            int hops = packet.destIP;
            if (postNegotiationSlaveforwardingScheme.get(hops) == sourceIP) {
                // You have to forward this time
                packet.destIP += 1;
                try {
                    sendSmallPacket(packet);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                try {
                    wait((postNegotiationSlaveforwardingScheme.size()-hops-1)*SHORT_PACKET_TIMESLOT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            } else {
                try {
                    wait((postNegotiationSlaveforwardingScheme.size()-hops)*SHORT_PACKET_TIMESLOT);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            try {
                startRequestSlavePhase();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }


    private void detectInterference(long delay) {
        boolean interference = false;
        if (sending) {
            sending = false;
            if (messagesToSend.get(0).getType() == MessageType.DATA_SHORT && delay > SHORT_PACKET_TIMESLOT) {
                interference = true;
            } else if (messagesToSend.get(0).getType() == MessageType.DATA && delay > LONG_PACKET_TIMESLOT) {
                interference = true;

            }
            if (interference) {
                System.out.println("\u001B[31mINTEFERFERENCE DETECTED\u001B[0m");


                if (messagesToSend.get(0).getType() == MessageType.DATA_SHORT) {
                    SmallPacket packet = readSmallPacket(messagesToSend.get(0).getData().array());
                    if (packet.negotiate && packet.broadcast && !packet.request && !packet.ackFlag) {
                        // Our negotiation packet had interference, get rid of it
                        messagesToSend.remove(0);
                    } else if (packet.negotiate && packet.broadcast && packet.request && !packet.ackFlag) {
                        // Our discovery packet had interference
                        messagesToSend.remove(0);
                        timer.stop();
                        exponential_backoff*=2;
                        startDiscoveryPhase(exponential_backoff);
                    } else if (!packet.negotiate && !packet.request && packet.ackFlag) {
                        // ACK packet. Get rid of it
                        messagesToSend.remove(0);
                    } else if (packet.broadcast && packet.SYN) {
                        messagesToSend.remove(0);
                        // Timing master packet. Rebroadcast!
                        try {
                            timer.stop();
                            startTimingMasterPhase(negotiation_phase_length);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                } else {
                    System.out.println("Keeping DATA packet in buffer");
                }

            } else {
                SmallPacket packet = readSmallPacket(messagesToSend.get(0).getData().array());
                if (state==State.NEGOTIATION_STRANGER && packet.negotiate && packet.broadcast && !packet.request && !packet.ackFlag) { // TODO possibly incorrect if
                    timer.stop();
                    startNegotiationStrangerDonePhase();
                }

                if (sentMessages.size() >= 1000) {
                    sentMessages.remove(0);
                }
                sentMessages.add(messagesToSend.remove(0));
            }
        }



    }



    public <E> List<E> decodePermutationOfThree(int order, List<E> list) {
        // Obviously these are just permutations of a list of 3 items. Using some abstract algebra, you wouldn't need to hardcode this
        // But that is outside the scope of this course
         switch (order) {
             case 0:
                 return new ArrayList<E>(){}; // -1 -1
             case 1:
                 return new ArrayList<E>(Arrays.asList(list.get(0))); // 0 -1
             case 2:
                 return new ArrayList<E>(Arrays.asList(list.get(1))); // 1 -1
             case 3:
                 return new ArrayList<E>(Arrays.asList(list.get(2))); // 2 -1
             case 4:
                 return new ArrayList<E>(Arrays.asList(list.get(0),list.get(1))); // 0 1
             case 5:
                 return new ArrayList<E>(Arrays.asList(list.get(0),list.get(2))); // 0 2
             case 6:
                 return new ArrayList<E>(Arrays.asList(list.get(1),list.get(0))); // 1 0
             case 7:
                 return new ArrayList<E>(Arrays.asList(list.get(1),list.get(2))); // 1 2
             case 8:
                 return new ArrayList<E>(Arrays.asList(list.get(2),list.get(0))); // 2 0
             case 9:
                 return new ArrayList<E>(Arrays.asList(list.get(2),list.get(1))); // 2 1
         }
        return null;
    }

    public int encodePermutationOfThree(int a, int b) {
        switch (a) {
            case 0:
                switch (b) {
                    case 1:
                        return 4;
                    case 2:
                        return 5;
                    case -1:
                        return 1;
                }
                break;
            case 1:
                switch (b) {
                    case 0:
                        return 6;
                    case 2:
                        return 7;
                    case -1:
                        return 2;
                }
                break;
            case 2:
                switch (b) {
                    case 0:
                        return 8;
                    case 1:
                        return 9;
                    case -1:
                        return 3;
                }
                break;
            case -1:
                return 0;
        }
        return -1;
    }

    public <E> List<E> decodePermutationOfTwo(int order, List<E> list) {
        // See description of permutationOfThree
        switch (order) {
            case 0:
                return new ArrayList<E>(){}; // -1 -1
            case 1:
                return new ArrayList<E>(Arrays.asList(list.get(0))); // 0 -1
            case 2:
                return new ArrayList<E>(Arrays.asList(list.get(1))); // 1 -1
            case 3:
                return new ArrayList<E>(Arrays.asList(list.get(0),list.get(1))); // 0 1
            case 4:
                return new ArrayList<E>(Arrays.asList(list.get(1),list.get(0)));// 1 0
        }
        return null;
    }

    public int encodePermutationOfTwo(int a, int b) {
        if (a==-1 && b==-1) {
            return 0;
        } else if (a==0 && b==-1) {
            return 1;
        } else if (a==1 && b==-1) {
            return 2;
        } else if (a==0 && b==1) {
            return 3;
        } else if (a==1 && b==0) {
            return 4;
        }
        return -1;
    }

}

