package framework;

import client.Message;
import client.MessageType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class PacketHandling {
    final BlockingQueue<Message> sendingQueue;
    public int SHORT_PACKET_TIMESLOT = 260;
    public int LONG_PACKET_TIMESLOT = 1510;
    List<Byte> splitPacketBuffer = new ArrayList<>();
    List<Message> messagesJustSent = new ArrayList<>();
    List<Message> messageHistory = new ArrayList<>();
    boolean sending = false;

    public PacketHandling(BlockingQueue<Message> sendingQueue) {
        this.sendingQueue = sendingQueue;
    }


    public void sendPacket(BigPacket packet) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(32);
        byte[] packetBytes = fillBigPacket(packet);
        toSend.put(packetBytes, 0, 32);
        Message msg = new Message(MessageType.DATA, toSend);
        sending = true; // Used for interference detection
        messagesJustSent.add(0, msg); // Used for interference detection
        sendingQueue.put(msg);
    }

    public void sendSmallPacket(SmallPacket packet) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(2);
        byte[] packetBytes = fillSmallPacket(packet);
        toSend.put(packetBytes, 0, 2); // jave includes newlines in System.in.read, so -2 to ignore this
        Message msg = new Message(MessageType.DATA_SHORT, toSend);
        sending = true;
        messagesJustSent.add(0, msg);
        sendingQueue.put(msg);
    }

    public byte[] appendToBuffer(BigPacket packet) {
        // When dealing with packets that contain a large payload that has been split up, this method can be used
        // It appends the payload of the packet to the buffer. If this packet no longer has the "more packets coming"
        // flag set, which means it's the last packet of this split payload. In that case, return the buffer contents.
        for (int i = 0; i < packet.payloadWithoutPadding.length; i++) {
            splitPacketBuffer.add(packet.payloadWithoutPadding[i]);
        }

        if (packet.morePackFlag) {
            return new byte[]{}; // Return an empty list to show that the buffer has not been emptied yet
        } else {
            byte[] bufferCopy = new byte[splitPacketBuffer.size()];
            for (int i = 0; i < splitPacketBuffer.size(); i++) {
                bufferCopy[i] = splitPacketBuffer.get(i);
            }
            splitPacketBuffer.clear();
            return bufferCopy;
        }
    }

    public byte[] fillSmallPacket(SmallPacket packet) {
        // Used to fill 2 bytes of data from a SmallPacket object.
        byte first_byte = (byte) (packet.sourceIP << 6 | packet.destIP << 4 | (packet.ackFlag ? 1 : 0) << 3 | (packet.request ? 1 : 0) << 2 | (packet.broadcast ? 1 : 0) << 1 | (packet.SYN ? 1 : 0));
        byte second_byte = (byte) ((packet.negotiate ? 1 : 0) << 7 | packet.ackNum);
        return new byte[]{first_byte, second_byte};
    }

    public byte[] fillBigPacket(BigPacket packet) {
        // Used to fill 32 bytes of data from a BigPacket object.
        byte[] result = new byte[32];
        byte[] first_two_bytes = fillSmallPacket(packet);
        result[0] = first_two_bytes[0];
        result[1] = first_two_bytes[1];
        result[2] = (byte) ((packet.morePackFlag ? 1 : 0) << 7 | packet.seqNum);
        result[3] = (byte) (packet.hops << 6 | (packet.size - 1)); // three bits left here
        for (int i = 4; i <= 31; i++) {
            result[i] = packet.payload[i - 4];
        }
        return result;
    }

    public SmallPacket readSmallPacket(byte[] bytes) {
        // Reads 2 bytes and turns it into a SmallPacket object
        SmallPacket packet = new SmallPacket();
        packet.sourceIP = (bytes[0] >> 6) & 0x03;
        packet.destIP = (bytes[0] >> 4) & 0x03;
        packet.ackFlag = ((bytes[0] >> 3) & 0x01) == 1;
        packet.request = ((bytes[0] >> 2) & 0x01) == 1;
        packet.broadcast = ((bytes[0] >> 1) & 0x01) == 1;
        packet.SYN = (bytes[0] & 0x01) == 1;

        packet.negotiate = ((bytes[1] >> 7) & 0x01) == 1;
        packet.ackNum = bytes[1] & 0x7F;

        return packet;
    }

    public BigPacket readBigPacket(byte[] bytes) {
        // Reads 32 bytes and turns it into a SmallPacket object
        SmallPacket smallPacket = readSmallPacket(bytes);
        boolean morePackFlag = ((bytes[2] >> 7) & 0x01) == 1;
        int seqNum = bytes[2] & 0x7F;
        int size = (bytes[3] & 0b00011111) + 1;
        int hops = (bytes[3] & 0b11000000) >> 6;
        if (size > 32) {
            System.err.println("Packet size is too big");
        }
        byte[] payload = new byte[28];
        byte[] payloadWithoutPadding = new byte[size - 4];

        for (int i = 4; i <= 31; i++) {
            payload[i - 4] = bytes[i];
            if (i < size) {
                payloadWithoutPadding[i - 4] = bytes[i];
            }
        }
        BigPacket packet = new BigPacket(smallPacket.sourceIP, smallPacket.destIP, smallPacket.ackNum, smallPacket.ackFlag, smallPacket.request, smallPacket.negotiate, smallPacket.SYN, smallPacket.broadcast, payload, payloadWithoutPadding, seqNum, morePackFlag, size, hops);
        return packet;
    }

    public void printByteBuffer(byte[] bytes, boolean buffered) {
        // If you receive a packet, this method is useful for debugging info. Prints the info of each packet.
        if (bytes!=null) {
            if (buffered) { // We aren't printing the contents of a packet, but of a buffer.
                System.out.print("\nBUFFERED, COMPLETE MESSAGE: ");
                for (byte aByte: bytes) {
                    System.out.print((char) aByte);
                }
            } else if (bytes.length == 2) { // Small packet
                SmallPacket packet = readSmallPacket(bytes);
                System.out.print("\nHEADER: ");
                for (byte aByte : bytes) {
                    System.out.print(String.format("%8s", Integer.toBinaryString(aByte & 0xFF)).replace(' ', '0') + " ");
                }
                System.out.print("\nHEADER FLAGS: " + (packet.ackFlag? "ACK ":"") + (packet.request? "REQUEST ":"") + (packet.negotiate? "NEGOTIATION ":"") + (packet.SYN? "SYN ":"") + (packet.broadcast? "BROADCAST ":""));
                System.out.print("\nSOURCE IP: " + packet.sourceIP);
                System.out.print("\nDEST IP: " + packet.destIP);
                System.out.print("\nACK NUM: " + packet.ackNum);
            } else { // Large packet
                BigPacket packet = readBigPacket(bytes);
                System.out.print("\nHEADER: ");
                for (int i = 0; i < 4; i++) {
                    byte aByte = bytes[i];
                    System.out.print(String.format("%8s", Integer.toBinaryString(aByte & 0xFF)).replace(' ', '0') + " ");
                }
                // ack req neg syn broadcast, ack number

                System.out.print("\nHEADER FLAGS: " + (packet.ackFlag? "ACK ":"") + (packet.request? "REQUEST ":"") + (packet.negotiate? "NEGOTIATION ":"") + (packet.SYN? "SYN ":"") + (packet.broadcast? "BROADCAST ":"") + (packet.morePackFlag? "MOREPACKETS ":""));
                System.out.print("\nSOURCE IP: " + packet.sourceIP);
                System.out.print("\nDEST IP: " + packet.destIP);
                System.out.print("\nACK NUM: " + packet.ackNum);
                System.out.print("\nSEQ NUM: " + packet.seqNum);
                System.out.print("\nHOPS: " + packet.hops);
                System.out.print("\nSIZE: " + packet.size);
                System.out.print("\nPAYLOAD: ");
                for (int i = 4; i < bytes.length; i++) {
                    byte aByte = bytes[i];
                    System.out.print((char) aByte);
                }
            }
        System.out.print("\n");
        System.out.print("\n");
        }
    }
}
