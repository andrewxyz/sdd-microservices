/*
 * Holds a TCP port until killed, so the preflight test can prove up.sh detects an occupied port.
 * Single-file source program: run with `java scripts/tests/Listen.java <port>` on JDK 21.
 */
import java.net.ServerSocket;

public class Listen {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java Listen.java <port>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        try (ServerSocket socket = new ServerSocket(port)) {
            System.out.println("listening on " + socket.getLocalPort());
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
