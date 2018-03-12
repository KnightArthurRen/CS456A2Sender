import java.net.InetAddress;

/**
 * Created by renyi on 2018-03-11.
 */
public class Main {
    public static void main(String[] args) {
        if(args.length < 4) {
            System.err.print("Sender: not enough arguments!");
        }
        try{
            InetAddress emulator_ip = InetAddress.getByName(args[0]);
//            Timeout limit
            long timeout = 100;
            Sender sender = new Sender(emulator_ip,Integer.parseInt(args[1]),Integer.parseInt(args[2]),args[3],timeout);
        } catch (java.net.UnknownHostException e) {
            System.err.println("Sender: unknow host!");
        }

    }
}
