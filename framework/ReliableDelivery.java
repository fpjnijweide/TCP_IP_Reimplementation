package framework;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.lang.Thread.sleep;

public class ReliableDelivery {
    // variables needed for reliable delivery
    int last_ack_sent = 0;
    int packet_number = 0;
    int last_seqNum_sent = 0;
    int last_ack_received = 0;
    int biggest_sendable_packet = 15;
    List<BigPacket> queueingPackets = new ArrayList<>(); // Needed to put packets in queue when seuqnce number is not in sender window
    static final int TIMEOUT = 10000; // timeout length after which a packet is retransfered when no ACK for that packet has been received
    static final int k = 15; // window size  = 15 packets
    List<Boolean> acknowledged = new ArrayList<>(Collections.nCopies(k, false)); // List to register which packets have been acknowledged
    List<Boolean> received = new ArrayList<>(Collections.nCopies(k, false)); // list to register which packets have been received by receiver
    PacketHandling packetHandling; // needed to be able to send packets later

    public ReliableDelivery(PacketHandling packetHandling) {
        this.packetHandling = packetHandling;
    }

    public void sendPackBack(SmallPacket ackPacket) throws InterruptedException { // when ACK has been received, send next available packet
        System.out.println("Ack received with acknum = " + ackPacket.ackNum);
        last_ack_received = ackPacket.ackNum; // save number of last ack received
        int SWS = 15; // set sender window size to 15
        biggest_sendable_packet = last_ack_received + SWS; // calculate biggest sendable packet using LAR & SWS
        acknowledged.set(last_ack_received, true); // put number of ack that has been received on true to check later if ACK of that packet has been received
        if(last_ack_received>0) { // if > 0, ack belongs to packet stream
            acknowledged.set(last_ack_received - 1, false); // so reset previous ack number to false to be able to resend packets that belong to other datastream with same sequence number
        }
        // go through queued packets that couldn't be sent due to them falling out of sender window, as long as there are packets in the que and the first packet still fits within the window
        while (queueingPackets.size()>0 && queueingPackets.get(0).seqNum <= biggest_sendable_packet){
            packetHandling.sendPacket(queueingPackets.get(0)); // send packet at current place in queue
            queueingPackets.remove(0); // and remove from queue
        }
        if (queueingPackets.size() == 0){ // if the queueing size is 0, reset the last sequence number to 0, for new packet stream
            last_seqNum_sent = 0; // reset last sequence number sent
        }
    }

    public void sendAckBack(BigPacket packet) throws InterruptedException { // method to return an ACK
        int RWS = 15; // receiver window size
        int end_of_receiver_window = last_ack_sent + RWS; // calculate biggest receivable packet using LAS & RWS
        last_ack_sent = packet.ackNum;

        if (packet.seqNum <= end_of_receiver_window) { // if packet with sequence number n fits in receiver window
            received.set(packet.seqNum, true); // put number of packet that has been received on true to check later if we should discard or keep received packet
            packet.ackNum = packet.seqNum; // save number of last packet received
            packet.ackFlag = true; // put ACK flag on true to send an ACK packet
            // send ack
            packetHandling.sendSmallPacket(new SmallPacket(packet.destIP, packet.sourceIP, packet.seqNum, packet.ackFlag, false, false, false, false));
            System.out.println("Ack sent with acknumber " + packet.seqNum);
        }
        // if packet did not fit within receiver window, discard packet
        else{ System.out.println("Packet with seq. number " + packet.ackNum + " is discarded");}
    }

    public void TCPsend(int read, ByteBuffer text, Routing routing, PacketHandling packetHandling, int destIP, boolean broadcast) throws InterruptedException {
        // method to send packet and check for incoming ACKs
        for (int i = 0; i < read - 1; i += 28) {// divide packets that do not fit into 1 packet (28 message bytes possible per packet)
            byte[] partial_text = read - 1 - i > 28 ? new byte[28] : new byte[read - 1 - i];
            System.arraycopy(text.array(), i, partial_text, 0, partial_text.length);
            boolean morePacketsFlag = read-1-i>28; // if message greater than 28 bytes, more packets are coming
            int size = morePacketsFlag? 32 : read-1-i+4; // make size according to length of message

            // create new big packet
            BigPacket packet_waiting_to_send = new BigPacket(routing.sourceIP, destIP, 0, false, false, false, false, broadcast, partial_text, packet_number, morePacketsFlag, size,0);

            if(packet_number < last_ack_received || packet_number > biggest_sendable_packet){// check if packet fits in sender window
                System.out.println("packet not in sending window, send later");
                queueingPackets.add(packet_waiting_to_send); // if it doesn't fit in sender window, put it in a queue to send later
            }
            else {// if packet did fit in sender window, send packet
                packetHandling.sendPacket(packet_waiting_to_send);
                makeTimeout(packet_waiting_to_send); // create timeout, check after 10 seconds if ack received for this packet
                last_seqNum_sent = packet_number;
                acknowledged.set(packet_number, false); // put ack for this packet on false
            }
            if (packet_waiting_to_send.morePackFlag){ // if the packet that has just been sent will be followed by more packets, increase packet number
                packet_number++;
            } else { // if no more packets coming:
                packet_number = 0; // reset packet number
                last_ack_received = 0; // reset last ack received
                biggest_sendable_packet = 15; // reset biggest sendable packet
                for(int j = 0; j < last_seqNum_sent; j++){ // reset list which keeps track of which packets have been received
                    received.set(j,false);
                }
            }
        }
    }

    private void makeTimeout(final BigPacket packet_waiting_to_send) { // handle timeout of packet
        Timer timer = new Timer(TIMEOUT, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent arg0) {
                if(!acknowledged.get(packet_waiting_to_send.seqNum)) {// if we did not receive an ACK for packet
                    try {
                        packetHandling.sendPacket(packet_waiting_to_send); // send packet again
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    System.out.println("resending packet" + packet_waiting_to_send.seqNum);
                    makeTimeout(packet_waiting_to_send); // create another timeout for this packet

                }
            }
        });
        timer.setRepeats(false);
        timer.start();
    }

    public void TCPreceive(BigPacket packet) throws InterruptedException { // handle receiving of packets
        if(!received.get(packet.seqNum)) { // if we receive a packet that has not been received before
            sleep(packetHandling.SHORT_PACKET_TIMESLOT*10); // wait a while to avoid interference
            sendAckBack(packet); // send an ACK of packet
            // if more packets are coming or if there are packets that belong to the same stream are in buffer.
            // 'or' is necessary to go in if-statement when there is no morePackFlag for first time, so when the last packet that belongs to a packet stream has arrived
            if (packet.morePackFlag || packetHandling.splitPacketBuffer.size() > 0) {
                byte[] result = packetHandling.appendToBuffer(packet); // append to buffer, result = output of buffer
                if (result.length > 0) {
                    packetHandling.printByteBuffer(result, true);  // print all buffered data
                }
            }
        } else { // if duplicate packet received, print that and send an ack back to prevent unnecessary retransmissions
            System.out.println("Duplicate packet");
            sendAckBack(packet);
        }
        if(!packet.morePackFlag){
            for( int i =0; i<k;i++){
                // put everything on false again to make sure next packet with seq num 0 that belongs to different stream gets accepted
                received.set(i,false);
            }
        }
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
