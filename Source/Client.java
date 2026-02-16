package Source;

public class Client {
   public static void main(String[] args){
    System.out.println("Hello World Client 5");
    try {
    Thread.sleep(60000); // 60,000 milliseconds = 60 seconds
    } catch (InterruptedException e) {
        e.printStackTrace();
    }
   }
}
