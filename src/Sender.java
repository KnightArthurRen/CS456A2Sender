//Created by renyi on 2018-03-11.

import java.io.FileWriter;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class Sender {
    private DatagramSocket socket;
    private InetAddress emulator_ip;
    private String contents;
    private int emulator_port;
    private int windowqueue = 0;
    private List<packet> UnACKQueue;
    private final Sending Sending;
    private final Receiving Receiving;
    private ReentrantLock queue_lock;
    private boolean shutdown = false;
    private FileWriter seqnum,ack;
    private List<Long> timer;
    private long timeout;

//    Subclass for Receivng Thread
    class Receiving implements Runnable {
        private DatagramSocket socket;
        private DatagramPacket buffer;
        private packet received_packet;
        private Receiving(DatagramSocket socket) {
            this.socket = socket;
            byte [] buf = new byte[1000];
            this.buffer = new DatagramPacket(buf,buf.length);
        }

        @Override
        public void run() {
            while(true) { // and waiting for all sended
//                Received the package
                try {
                    socket.receive(buffer);
                } catch (java.io.IOException e) {
                    System.err.println("Sender: Receiving package failed!");
                }
                try {
                    received_packet = packet.parseUDPdata(buffer.getData());
                } catch (java.lang.Exception e) {
                    System.err.println("Sender: Received package cannot be parsed");
                }

//                Check the output
                if(received_packet.getType() == 2) {
                    Sender.this.queue_lock.lock();
                    Sender.this.shutdown = true;
                    Sender.this.queue_lock.unlock();
                    break;
                }

                Sender.this.queue_lock.lock();
//                Record the log
                String log = String.valueOf(received_packet.getSeqNum());
                log += "\n";
                try{
                    Sender.this.ack.write(log);
                } catch (java.io.IOException e) {
                    System.err.println("Sender: failed to log ack");
                }

//                First loop check if the ack is duplicate or not
                boolean duplicate = true;
                int i = 0;
                for(; i < Sender.this.UnACKQueue.size(); i++) {
                    if(Sender.this.UnACKQueue.get(i).getSeqNum() == received_packet.getSeqNum()) {
                        duplicate = false;
                        break;
                    }
                }
//                if it's not a duplicate, ack everything up to the ack seq_num
                if (!duplicate){
                    Sender.this.UnACKQueue.subList(0,i).clear();
                    Sender.this.timer.subList(0,i).clear();
                }
                Sender.this.queue_lock.unlock();
            }
        }
}
//
//    Subclass for Sending Thread
    class Sending implements Runnable {
        private DatagramSocket socket;
        private int seqnum;
        private int content_length;
        private packet new_packet;
        private Sending(DatagramSocket socket) {
            this.socket = socket;
            seqnum = 0;
        }
        private void send_pkg(packet p){
            //                        send the package
            Sender.this.windowqueue++;
            Sender.this.UnACKQueue.add(new packet(p));
            DatagramPacket binary = new DatagramPacket(p.getUDPdata(),p.getUDPdata().length, emulator_ip,emulator_port);
            try{
                socket.send(binary);
            } catch (java.io.IOException e) {
                System.err.println("Sender: failed to send new packet");
            }
//            Add timer
            Sender.this.timer.add(System.nanoTime());
//                        Record the log
            String log = String.valueOf(p.getSeqNum());
            log += "\n";
            try{
                Sender.this.seqnum.write(log);
            } catch (java.io.IOException e) {
                System.err.println("Sender: failed to log seqnum");
            }


        }
        @Override
        public void run() {
            content_length = Sender.this.contents.length();
//                Parse the entire content and send it with 500 character chunks each and send
            for(int index = 0; index < content_length; index += 500) {
//                    Create the packet
                try {
                    if(index + 500 >= content_length) {
                        new_packet = packet.createPacket(seqnum % 32, Sender.this.contents.substring(index));
                        seqnum++;
                    } else {
                        new_packet = packet.createPacket(seqnum % 32, Sender.this.contents.substring(index,index + 500));
                        seqnum++;
                    }
                } catch (java.lang.Exception e) {
                    System.err.println("Sender: create new packet failed!");
                }

                while(true) {
                    if(Sender.this.windowqueue < 10) {
                        Sender.this.queue_lock.lock();
                        send_pkg(new_packet);
                        Sender.this.queue_lock.unlock();
                        break;
                    } else if(System.nanoTime() - Sender.this.timer.get(0) < timeout) {
                        //                    If there is one time out, resend all after timeout
                        Sender.this.queue_lock.lock();
                        Sender.this.windowqueue = 0;
                        Sender.this.timer.clear();
                        List<packet> ResentList = new ArrayList<>();
                        for(packet p : Sender.this.UnACKQueue) {
                            ResentList.add(new packet(p));
                        }
                        Sender.this.UnACKQueue.clear();
                        for(packet p : ResentList) {
                            send_pkg(p);
                        }
                        Sender.this.queue_lock.unlock();
                    }
                }
            }
//            After all the packages are send, send the EOT
            try{
                new_packet = packet.createEOT(seqnum % 32);
                long eot_timer = System.nanoTime();
                DatagramPacket binary = new DatagramPacket(new_packet.getUDPdata(),new_packet.getUDPdata().length, emulator_ip,emulator_port);
                while(true) {
                    Sender.this.queue_lock.lock();
                    if(Sender.this.shutdown) {
                        Sender.this.queue_lock.unlock();
                        break;
                    }
                    Sender.this.queue_lock.unlock();
                    if(System.nanoTime() - eot_timer >  timeout) {
                        try {
                            socket.send(binary);
                        } catch (java.io.IOException e) {
                            System.err.println("Sender: cannot sent EOT");
                        }
                        eot_timer = System.nanoTime();
                    }
                }
            } catch (java.lang.Exception e) {
                System.err.println("Sender: create EOT failed!");
            }
        }
}
//    Constructor
    public Sender(InetAddress emulator_ip, int emulator_port, int sender_receive_port,String filename,long timeout) {
//        Construct the socket
        this.emulator_ip = emulator_ip;
        this.emulator_port = emulator_port;
        this.queue_lock = new ReentrantLock();
        this.UnACKQueue = new ArrayList<>(10);
        this.timer = new ArrayList<>();
        this.timeout =timeout;
        try {
            socket = new DatagramSocket(sender_receive_port);
        } catch (java.net.SocketException e) {
            System.err.println("Sender: the port is not avaliable");
        }

//        Read in the entire file
        try {
            contents = new String(Files.readAllBytes(Paths.get(filename)));
        } catch (java.io.IOException e) {
            System.err.println("Sender: cannot open the file!");
            e.printStackTrace();
        }

//        Create logs
        try {
            seqnum = new FileWriter("seqnum.log");
            ack = new FileWriter("ack.log");
        } catch (java.io.IOException e) {
            System.err.println("Sender: failed to create log files");
        }
//        Create the threads
        Receiving = new Receiving(socket);
        Thread receive = new Thread(Receiving);
        receive.start();
        Sending = new Sending(socket);
        Thread send = new Thread(Sending);
        send.start();
//        Wait for both to finish
        try {
            receive.join();
            send.join();
        } catch (Exception e) {
            System.err.println("Sender: waiting thread crashed!!!");
        }
    }

}
