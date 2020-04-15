package framework;

public class BigPacket extends SmallPacket {
    // Third byte
    boolean morePackFlag;
    int seqNum; // 7 bit number, range [0,127]

    // Fourth byte
    int size; // 5 bit number, range [0,31]
    int hops; // 2 bit number, range [0,3]

    // Other bytes
    byte[] payload;
    byte[] payloadWithoutPadding;

    public BigPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast, byte[] payloadWithoutPadding, int seqNum, boolean morePackFlag, int size, int hops) {
        super(sourceIP, destIP, ackNum, ackFlag, request, negotiate, SYN, broadcast);
        this.payload = new byte[28];
        for (int j = 0; j < size-4; j++) {
            payload[j] = payloadWithoutPadding[j];
        }
        this.payloadWithoutPadding = payloadWithoutPadding;
        this.seqNum = seqNum;
        this.morePackFlag = morePackFlag;
        this.size = size;
        this.hops = hops;
    }

    public BigPacket(int sourceIP, int destIP, int ackNum, boolean ackFlag, boolean request, boolean negotiate, boolean SYN, boolean broadcast, byte[] payload, byte[] payloadWithoutPadding, int seqNum, boolean morePackFlag, int size, int hops) {
        super(sourceIP, destIP, ackNum, ackFlag, request, negotiate, SYN, broadcast);
        this.payload = payload;
        this.payloadWithoutPadding = payloadWithoutPadding;
        this.seqNum = seqNum;
        this.morePackFlag = morePackFlag;
        this.size = size;
        this.hops = hops;
    }
}
