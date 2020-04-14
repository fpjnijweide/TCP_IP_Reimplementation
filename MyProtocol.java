import client.*;

import javax.swing.*;
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

    public enum State{
        NULL,
        DISCOVERY,
        SENT_DISCOVERY,

        // etc
        NEGOTIATION_MASTER,
        READY,
        TIMING_SLAVE,
        TIMING_MASTER, TIMING_STRANGER, NEGOTIATION_STRANGER, POST_NEGOTIATION_MASTER, WAITING_FOR_TIMING_STRANGER, NEGOTIATION_STRANGER_DONE;
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
                    sourceIP = 0;
                    try {
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

    private void startTimingMasterPhase() throws InterruptedException {
        startTimingMasterPhase(0);
    }

    private void startTimingMasterPhase(int new_negotiation_phase_length) throws InterruptedException {
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

    private void startNegotiationMasterPhase() throws InterruptedException {
        setState(State.NEGOTIATION_MASTER);
        negotiatedPackets.clear();
        wait(this.negotiation_phase_length*SHORT_PACKET_TIMESLOT);
        startPostNegotiationMasterPhase();
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


    // TODO implement read side of all of these

    private void startPostNegotiationMasterPhase() {
        setState(State.POST_NEGOTIATION_MASTER);
        // todo collect all the packets from buffer..?
        // todo implement
    }

    private void startPostNegotiationSlavePhase(SmallPacket packet) {
        // TODO implement
    }

    private void startPostNegotiationStrangerPhase(SmallPacket packet) {
    }



    private void startTimingSlavePhase() {
        startTimingSlavePhase(0);
    }

    private void startTimingSlavePhase(int new_negotiation_phase_length) {
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

    private void startNegotiationStrangerPhase() throws InterruptedException {
        setState(State.NEGOTIATION_STRANGER);
        this.negotiation_phases_encountered++;
        for (int i = 0; i < this.negotiation_phase_stranger_length; i++) {
            if (state!=State.NEGOTIATION_STRANGER) {
                return;
            }
            float roll = new Random().nextFloat() *(1+ ((float)this.negotiation_phases_encountered-1)/10);
            if (roll > 0.25) {
                tiebreaker = new Random().nextInt(1 << 7);
                SmallPacket packet = new SmallPacket(0,0,tiebreaker,false,false,true,false,true);
                sendSmallPacket(packet);
            } else {
                wait(SHORT_PACKET_TIMESLOT);
            }
        }
        startWaitingForTimingStrangerPhase();



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
        // TODO rewrite deze functie. je wilt duidelijk hele packets bufferen (zodat je later bijvoorbeeld kan kijken wat de laatste sequence nr is die je krijgt enzo)
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
                                startPostNegotiationSlavePhase(packet); // TODO implement ack nr is forwarding route and hops
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
                                startPostNegotiationStrangerPhase(packet); // TODO implement ack nr is forwarding route and hops
                            }
                        }
                        break;
                }
                break;
            case POST_NEGOTIATION_MASTER:
                // TODO we shouldn't receive anything here?
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




    private void detectInterference(long delay) {
        boolean interference = false;
        if (sending) {
            sending = false;
            if (messagesToSend.get(0).getType() == MessageType.DATA_SHORT && delay > 251) {
                interference = true;
            } else if (messagesToSend.get(0).getType() == MessageType.DATA && delay > 1500) {
                interference = true;

            }
            if (interference) {
                System.out.println("\u001B[31mINTEFERFERENCE DETECTED");


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



    public <E> List<E> permutationOfThree(int order, List<E> list) {
        // Obviously these are just permutations of a list of 3 items. Using some abstract algebra, you wouldn't need to hardcode this
        // But that is outside the scope of this course
         switch (order) {
             case 0:
                 return new ArrayList<E>(){};
             case 1:
                 return new ArrayList<E>(Arrays.asList(list.get(0)));
             case 2:
                 return new ArrayList<E>(Arrays.asList(list.get(1)));
             case 3:
                 return new ArrayList<E>(Arrays.asList(list.get(2)));
             case 4:
                 return new ArrayList<E>(Arrays.asList(list.get(0),list.get(1)));
             case 5:
                 return new ArrayList<E>(Arrays.asList(list.get(0),list.get(2)));
             case 6:
                 return new ArrayList<E>(Arrays.asList(list.get(1),list.get(0)));
             case 7:
                 return new ArrayList<E>(Arrays.asList(list.get(1),list.get(2)));
             case 8:
                 return new ArrayList<E>(Arrays.asList(list.get(2),list.get(0)));
             case 9:
                 return new ArrayList<E>(Arrays.asList(list.get(2),list.get(1)));
         }
        return null;
    }

    public <E> List<E> permutationOfTwo(int order, List<E> list) {
        // See description of permutationOfThree
        switch (order) {
            case 0:
                return new ArrayList<E>(){};
            case 1:
                return new ArrayList<E>(Arrays.asList(list.get(0)));
            case 2:
                return new ArrayList<E>(Arrays.asList(list.get(1)));
            case 3:
                return new ArrayList<E>(Arrays.asList(list.get(0),list.get(1)));
            case 4:
                return new ArrayList<E>(Arrays.asList(list.get(1),list.get(0)));
        }
        return null;
    }

}

