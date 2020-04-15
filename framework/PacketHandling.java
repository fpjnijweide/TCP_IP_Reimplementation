package framework;

import client.Message;
import client.MessageType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class PacketHandling {
    final BlockingQueue<Message> sendingQueue;
    List<Byte> buffer = new ArrayList<>(); // TODO rename to split_packet_buffer
    List<Message> messagesToSend = new ArrayList<>();
    List<Message> sentMessages = new ArrayList<>(); // TODO might overflow
    boolean sending = false;
    public int SHORT_PACKET_TIMESLOT = 260;
    public int LONG_PACKET_TIMESLOT = 1510;

    public PacketHandling(BlockingQueue<Message> sendingQueue) {
        this.sendingQueue = sendingQueue;
    }


    public void sendPacket(BigPacket packet) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(32); // jave includes newlines in System.in.read, so -2 to ignore this
        byte[] packetBytes = fillBigPacket(packet);
        toSend.put(packetBytes, 0, 32); // jave includes newlines in System.in.read, so -2 to ignore this
        Message msg = new Message(MessageType.DATA, toSend);
        sending = true;
        messagesToSend.add(0,msg);
        sendingQueue.put(msg);
    }

    public void sendSmallPacket(SmallPacket packet) throws InterruptedException {
        ByteBuffer toSend = ByteBuffer.allocate(2);
        byte[] packetBytes = fillSmallPacket(packet);
        toSend.put( packetBytes, 0, 2); // jave includes newlines in System.in.read, so -2 to ignore this
        Message msg = new Message(MessageType.DATA_SHORT, toSend);
        sending = true;
        messagesToSend.add(0,msg);
        sendingQueue.put(msg);
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
        result[3] = (byte) (packet.hops << 6 | (packet.size-1)); // three bits left here
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
        int size = (bytes[3] & 0b00011111) + 1;
        int hops = (bytes[3] & 0b11000000) >> 6;
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
        BigPacket packet = new BigPacket(smallPacket.sourceIP, smallPacket.destIP, smallPacket.ackNum, smallPacket.ackFlag, smallPacket.request, smallPacket.negotiate, smallPacket.SYN, smallPacket.broadcast, payload, payloadWithoutPadding, seqNum, morePackFlag, size, hops);
        return packet;
    }
}
