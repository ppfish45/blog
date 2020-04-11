import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class Test {

    static List<byte[]> objs = new ArrayList<>();

    public static void main(String[] args) throws InterruptedException, IOException {
        
        int trialTime = 10;

        long pid = ProcessHandle.current().pid();

        System.out.println("Current pid = " + pid);
        System.in.read();

        while (trialTime > 0) {
        
            trialTime --;
            
            System.out.println("Trial remaining: " + trialTime);

            // 1024 mb
            int arraySize = 1024;

            for (int i = 0; i < arraySize; i++) {
                if (i + 1 > objs.size()) {
                    objs.add(new byte[1024 * 1024]);
                } else {
                    objs.set(i, new byte[1024 * 1024]);
                }
                if ((i + 1) % 128 == 0) {            
                    System.out.println("    " + (i + 1) + " mb...");
                }
                // make memory increment visible
                Thread.sleep(2);
            }

            System.out.println("Allocation finished.");
            Thread.sleep(2000);

            for (int i = 0; i < arraySize; i++) {
                // unlink the object so that it can be collected
                objs.set(i, null);
            }

            System.gc();
            System.out.println("GC finished.");

            Thread.sleep(2000);

        }

        System.in.read();
    }
}