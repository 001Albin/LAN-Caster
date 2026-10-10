package org.develop.lancaster.core.transfer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** The description of a file a sender wants to push to a receiver. */
public final class Offer {
    public final String transferId;
    public final String fileName;
    public final long fileSize;
    public final String senderName;
    public final String senderId;
    public final int blockSize;
    public final long lastModified;

    public Offer(String transferId, String fileName, long fileSize, String senderName,
                 String senderId, int blockSize, long lastModified) {
        this.transferId = transferId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.senderName = senderName;
        this.senderId = senderId;
        this.blockSize = blockSize;
        this.lastModified = lastModified;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeUTF(transferId);
        out.writeUTF(fileName);
        out.writeLong(fileSize);
        out.writeUTF(senderName == null ? "" : senderName);
        out.writeUTF(senderId == null ? "" : senderId);
        out.writeInt(blockSize);
        out.writeLong(lastModified);
    }

    public static Offer read(DataInputStream in) throws IOException {
        String id = in.readUTF();
        String name = in.readUTF();
        long size = in.readLong();
        String senderName = in.readUTF();
        String senderId = in.readUTF();
        int blockSize = in.readInt();
        long lastModified = in.readLong();
        if (size < 0 || blockSize <= 0 || blockSize > 256 * 1024 * 1024 || name.isEmpty()) {
            throw new IOException("Malformed offer");
        }
        return new Offer(id, name, size, senderName, senderId, blockSize, lastModified);
    }
}
