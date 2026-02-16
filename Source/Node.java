package Source;

public class Node {
   public static void main(String[] args){
    System.out.println("Hello World Node");
    try {
    Thread.sleep(60000); // 60,000 milliseconds = 60 seconds
    } catch (InterruptedException e) {
        e.printStackTrace();
    }
   } 
}
