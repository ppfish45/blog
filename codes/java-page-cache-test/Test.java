import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

class Test {
    public static void main(final String[] args) throws IOException, InterruptedException {
        final File file = new File("./500mb.txt");
        final InputStream reader = new FileInputStream(file);
        final int inputSize = 10 * 1024 * 1024;
        // pause the console
        System.in.read();
        int result = 0;
        // read 10 mb per iteration
        for (int i = 1; result != -1; i++) {
            for (int j = 0; j < inputSize && result != -1; j++) {
                result = reader.read();
            }
            System.out.println(10 * i + " mb ...");
            Thread.sleep(1000);
        }
    }
};
