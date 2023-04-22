package dev.hickel;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.util.concurrent.locks.LockSupport;


public class QueuedFileSender implements Runnable {
    private final String fileName;
    private final long fileSize;
    private final File file;
    private final int chunkSize;
    private final int blockSize;
    private final Socket socket;
    private final int transferLimit;

    public QueuedFileSender(File file, String address, int port, int transferLimit) throws IOException {
        fileSize = file.length();
        fileName = file.getName();
        this.file = file;
        chunkSize = Settings.chunkSize;
        blockSize = Settings.blockSize;
        socket = new Socket(address, port);
        socket.setSoTimeout(120_000);
        socket.setTrafficClass(24);
        this.transferLimit = transferLimit < 1 ? -1 : transferLimit;
    }

    @Override
    public void run() {
        try (DataOutputStream socketOut = new DataOutputStream(socket.getOutputStream());
             DataInputStream socketIn = new DataInputStream(socket.getInputStream())) {

            // Send file info
            socketOut.writeUTF(fileName);
            socketOut.writeLong(fileSize);
            socketOut.flush();

            boolean accepted = socketIn.readBoolean();
            if (!accepted) {
                Main.activeTransfers.remove(fileName);
                System.out.println("No space for, file already exists, or all paths in use: " + fileName
                                           + " will retry in: " + Settings.fileCheckInterval + " Seconds");
                return;
            }
            CircularBufferQueue bufferQueue = new CircularBufferQueue(file);
            new Thread(bufferQueue).start();
            System.out.println("Started transfer of file: " + fileName);


            // Loop through until the "null" size 0 array is returned from the buffer queue.
            byte[] currBuffer;
            int currSize;
            int byteWritten;
            long startTime = System.nanoTime();
            long totalTransferred = 0;
            while (true) {
                currBuffer = bufferQueue.poll();
                currSize = currBuffer.length;
                byteWritten = 0;
                if (currSize == 0) { break; }
                for (int i = 0; i < currSize; i += blockSize) {
                    int byteSize = (Math.min(currSize - byteWritten, blockSize));
                    socketOut.writeInt(byteSize);
                    socketOut.write(currBuffer, i, byteSize);
                    socketOut.flush();
                    byteWritten += byteSize;
                    totalTransferred += byteSize;
                }
                bufferQueue.finishedRead();
                if (transferLimit == -1) {
                    LockSupport.parkNanos(Settings.calcRateLimit(startTime, totalTransferred, transferLimit));
                }
            }
            socketOut.writeInt(-1); // Send EOF
            socketOut.flush();

            boolean success = socketIn.readBoolean(); // wait for servers last write, to avoid an exception on quick disconnect
            socket.close();
            if (success) {
                System.out.println("Finished transfer for file: " + fileName);
            } else {
                System.out.println("Error during finalization of file transfer");
                Main.activeTransfers.remove(fileName);
                throw new IllegalStateException("Server responded to end of transfer as failed");
            }
            if (Settings.deleteAfterTransfer) {
                Files.delete(file.toPath());
                System.out.println("Deleted file: " + file);
            }
            bufferQueue.close();
            Main.activeTransfers.remove(fileName);
        } catch (IOException e) {
            Main.activeTransfers.remove(fileName);
            System.out.println("Error in file transfer, most likely connection was lost.");
            e.printStackTrace();
            try { socket.close(); } catch (IOException ee) { System.out.println("Error closing socket"); }

        } catch (Exception e) {
            Main.activeTransfers.remove(fileName);
            e.printStackTrace();
            try { socket.close(); } catch (IOException ee) { System.out.println("Error closing socket"); }
        } finally {
            System.gc();
            if (socket != null) {
                try { socket.close(); } catch (IOException e) { System.out.println("Error closing socket"); }
            }
        }
    }
}
