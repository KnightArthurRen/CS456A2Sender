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

public class Sender_Class {
    private DatagramSocket socket;
    private InetAddress emulator_ip;
    private String contents;
    private int emulator_port;
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
                    System.err.println("Sender_Class: Receiving package failed!");
                }
                try {
                    received_packet = packet.parseUDPdata(buffer.getData());
                } catch (java.lang.Exception e) {
                    System.err.println("Sender_Class: Received package cannot be parsed");
                }

//                Check the output
//                If the package is EOT
                if(received_packet.getType() == 2) {
                    Sender_Class.this.queue_lock.lock();
                    Sender_Class.this.shutdown = true;
                    Sender_Class.this.queue_lock.unlock();
                    break;
                }

//                Record the log
                String log = String.valueOf(received_packet.getSeqNum());
                log += "\n";
                try{
                    Sender_Class.this.ack.write(log);
                } catch (java.io.IOException e) {
                    System.err.println("Sender_Class: failed to log ack");
                }

                Sender_Class.this.queue_lock.lock();
//                First loop check if the ack is duplicate or not
                boolean duplicate = true;
                int i = 0;
                for(; i < Sender_Class.this.UnACKQueue.size(); i++) {
                    if(Sender_Class.this.UnACKQueue.get(i).getSeqNum() == received_packet.getSeqNum()) {
                        duplicate = false;
                        break;
                    }
                }
//                if it's not a duplicate, ack everything up to the ack seq_num
                if (!duplicate){
                    if(i == 0) {
                        Sender_Class.this.UnACKQueue.remove(i);
                        Sender_Class.this.timer.remove(i);
                    } else {
                        Sender_Class.this.UnACKQueue.subList(0,i).clear();
                        Sender_Class.this.timer.subList(0,i).clear();
                    }
                }
                Sender_Class.this.queue_lock.unlock();
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
            Sender_Class.this.UnACKQueue.add(new packet(p));
            DatagramPacket binary = new DatagramPacket(p.getUDPdata(),p.getUDPdata().length, emulator_ip,emulator_port);
            try{
                socket.send(binary);
            } catch (java.io.IOException e) {
                System.err.println("Sender_Class: failed to send new packet");
            }
//            Add timer
            Sender_Class.this.timer.add(System.nanoTime());
//                        Record the log
            String log = String.valueOf(p.getSeqNum());
            log += "\n";
            try{
                Sender_Class.this.seqnum.write(log);
            } catch (java.io.IOException e) {
                System.err.println("Sender_Class: failed to log seqnum");
            }
        }
        @Override
        public void run() {
            content_length = Sender_Class.this.contents.length();
//                Parse the entire content and send it with 500 character chunks each and send
            for(int index = 0; index < content_length; index += 500) {
//                    Create the packet
                try {
                    if(index + 500 >= content_length) {
                        new_packet = packet.createPacket(seqnum % 32, Sender_Class.this.contents.substring(index));
                        seqnum++;
                    } else {
                        new_packet = packet.createPacket(seqnum % 32, Sender_Class.this.contents.substring(index,index + 500));
                        seqnum++;
                    }
                } catch (java.lang.Exception e) {
                    System.err.println("Sender_Class: create new packet failed!");
                }
                while(true) {
                    Sender_Class.this.queue_lock.lock();
                    if(Sender_Class.this.UnACKQueue.size() < 10) {
                        send_pkg(new_packet);
                        Sender_Class.this.queue_lock.unlock();
                        break;
                    } else if(System.nanoTime() - Sender_Class.this.timer.get(0) > timeout) {
                        //                    If there is one time out, resend all after timeout
                        Sender_Class.this.timer.clear();
                        List<packet> ResentList = new ArrayList<>();
                        for(packet p : Sender_Class.this.UnACKQueue) {
                            ResentList.add(new packet(p));
                        }
                        Sender_Class.this.UnACKQueue.clear();
                        for(packet p : ResentList) {
                            send_pkg(p);
                        }
                    }
                    Sender_Class.this.queue_lock.unlock();
                }
            }
//            After all pacakges send, wait for all packages got Ack
            while(true) {
                Sender_Class.this.queue_lock.lock();
//                If all is acked, proceed
                if (Sender_Class.this.UnACKQueue.size() == 0) break;
                if(System.nanoTime() - Sender_Class.this.timer.get(0) > timeout) {
                    //                    If there is one time out, resend all after timeout
                    List<packet> ResentList = new ArrayList<>();
                    for(packet p : Sender_Class.this.UnACKQueue) {
                        ResentList.add(new packet(p));
                    }
                    Sender_Class.this.timer.clear();
                    Sender_Class.this.UnACKQueue.clear();
                    for(packet p : ResentList) {
                        send_pkg(p);
                    }
                }
                Sender_Class.this.queue_lock.unlock();
            }
//            After all the packages are send, send the EOT
            try{
                new_packet = packet.createEOT(seqnum % 32);
                DatagramPacket binary = new DatagramPacket(new_packet.getUDPdata(),new_packet.getUDPdata().length, emulator_ip,emulator_port);
                try {
                    socket.send(binary);
                } catch (java.io.IOException e) {
                    System.err.println("Sender_Class: cannot sent EOT");
                }
            } catch (java.lang.Exception e) {
                System.err.println("Sender_Class: create EOT failed!");
            }
        }
}
//    Constructor
    public Sender_Class(InetAddress emulator_ip, int emulator_port, int Sender_Class_receive_port,String filename,long timeout) {
//        Construct the socket
        this.emulator_ip = emulator_ip;
        this.emulator_port = emulator_port;
        this.queue_lock = new ReentrantLock();
        this.UnACKQueue = new ArrayList<>(10);
        this.timer = new ArrayList<>();
        this.timeout =timeout;
        try {
            socket = new DatagramSocket(Sender_Class_receive_port);
        } catch (java.net.SocketException e) {
            System.err.println("Sender_Class: the port is not avaliable");
        }

//        Read in the entire file
        try {
            contents = new String(Files.readAllBytes(Paths.get(filename)));
        } catch (java.io.IOException e) {
            System.err.println("Sender_Class: cannot open the file!");
            e.printStackTrace();
        }

//        Create logs
        try {
            seqnum = new FileWriter("seqnum.log");
            ack = new FileWriter("ack.log");
        } catch (java.io.IOException e) {
            System.err.println("Sender_Class: failed to create log files");
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
            System.err.println("Sender_Class: waiting thread crashed!!!");
        }
//        Once finished, close the file writers
        try {
            seqnum.close();
            ack.close();
        } catch (java.io.IOException e) {
            System.err.println("Sender_Class: cannot close file writers!");
        }

    }

}
