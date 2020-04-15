package framework;

public class SmallPacket {
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
