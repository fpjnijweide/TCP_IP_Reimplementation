package framework;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ReliableDelivery {
    // TCP stuff
    int last_ack_sent = 0;
    int packet_number = 0;
    int last_seqNum_sent = 0;
    int last_ack_received = 0;
    int biggest_sendable_packet = 15;
    List<BigPacket> queueingPackets = new ArrayList<>();
    static final int TIMEOUT = 10000;
    boolean did_send_packet = false;
    static final int k = 15;
    List<Boolean> acknowledged = new ArrayList<>(Collections.nCopies(k, false));
    List<Boolean> received = new ArrayList<>(Collections.nCopies(k, false));

    PacketHandling packetHandling;

    public ReliableDelivery(PacketHandling packetHandling) {
        this.packetHandling = packetHandling;
    }

    public void sendPackBack(SmallPacket ackPacket) throws InterruptedException {


        System.out.println("Ack received with acknum = " + ackPacket.ackNum);
        last_ack_received = ackPacket.ackNum;
        int SWS = 15;
        biggest_sendable_packet = last_ack_received + SWS;
        acknowledged.set(last_ack_received, true);
        if(last_ack_received>0) {
            acknowledged.set(last_ack_received - 1, false);
        }
        while (queueingPackets.size()>0 && queueingPackets.get(0).seqNum <= biggest_sendable_packet){
            packetHandling.sendPacket(queueingPackets.get(0));
            queueingPackets.remove(0);
        }
        if (queueingPackets.size() == 0){
            last_seqNum_sent = 0; // reset last sequence number sent
        }
    }

    public void sendAckBack(BigPacket packet) throws InterruptedException {

        int RWS = 15;
        int end_of_receiver_window = last_ack_sent + RWS;
        last_ack_sent = packet.ackNum;

        if (packet.seqNum <= end_of_receiver_window) {
            int last_pack_received = packet.seqNum;

            received.set(packet.seqNum, true);
            packet.ackNum = packet.seqNum;
            packet.ackFlag = true;
            // send ack
            packetHandling.sendSmallPacket(new SmallPacket(packet.destIP, packet.sourceIP, packet.seqNum, packet.ackFlag, false, false, false, false));
            System.out.println("Ack sent with acknumber " + packet.seqNum);

        }
        else{ System.out.println("Packet with seq. number " + packet.ackNum + " is discarded");}


    }

    public void TCPsend(int read, ByteBuffer text, Routing routing, PacketHandling packetHandling, int destIP, boolean broadcast) throws InterruptedException {
        for (int i = 0; i < read - 1; i += 28) {
            byte[] partial_text = read - 1 - i > 28 ? new byte[28] : new byte[read - 1 - i];
            System.arraycopy(text.array(), i, partial_text, 0, partial_text.length);
//                        for (int j = 0; j < partial_text.length; j++) {
//                            partial_text[j] = text.array()[j+i];
//                        }
            boolean morePacketsFlag = read-1-i>28;
            int size = morePacketsFlag? 32 : read-1-i+4;

//                            received.set(packet_number,false);

            BigPacket packet_waiting_to_send = new BigPacket(routing.sourceIP, destIP, 0, false, false, false, false, broadcast, partial_text, packet_number, morePacketsFlag, size,0);
            did_send_packet = true;
            if(packet_number < last_ack_received || packet_number > biggest_sendable_packet){
                System.out.println("packet not in sending window, send later");
                queueingPackets.add(packet_waiting_to_send);
            }
            else {
                packetHandling.sendPacket(packet_waiting_to_send);
                makeTimeout(packet_waiting_to_send);
                last_seqNum_sent = packet_number;
                acknowledged.set(packet_number, false);
            }

            if (packet_waiting_to_send.morePackFlag){
                packet_number++;
            } else {
                packet_number = 0;
                last_ack_received = 0;
                biggest_sendable_packet = 15;
                for(int j = 0; j < last_seqNum_sent; j++){
                    received.set(j,false);
                }
            }
        }
    }

    private void makeTimeout(final BigPacket packet_waiting_to_send) {
        Timer timer = new Timer(TIMEOUT, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if(!acknowledged.get(packet_waiting_to_send.seqNum)) {
                    try {
                        packetHandling.sendPacket(packet_waiting_to_send);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    System.out.println("resending packet" + packet_waiting_to_send.seqNum);
                    makeTimeout(packet_waiting_to_send);

                }
            }
        });
        timer.setRepeats(false);
        timer.start(); // Go go g
    }

    public void TCPreceive(BigPacket packet) throws InterruptedException {
        if(!received.get(packet.seqNum)) {
            sendAckBack(packet);

            if (packet.morePackFlag || packetHandling.splitPacketBuffer.size() > 0) {
                byte[] result = packetHandling.appendToBuffer(packet);
                if (result.length > 0) {
                    packetHandling.printByteBuffer(result, true);
                }
            }
        } else {System.out.println("Duplicate packet");}
        if(!packet.morePackFlag){
            for( int i =0; i<k;i++){
                // put everything on false again to make sure next packet with seq num 0 that belongs to different stream gets accepted
                received.set(i,false);
            }
        }
        // TODO @Martijn sliding window protocol receiving side
    }

    public void TCPreceiveSmall(SmallPacket packet) {
        if(packet.ackFlag){//only send pack back if packet received is an ACK packet
            try {
                sendPackBack(packet);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
