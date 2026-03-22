package Services.ImageToAscii;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.imageio.ImageIO;


public class ImageToAsciiService {
//#%=+-*. 42.5
    private static final int ASCII_HEIGHT = 64;

    public static byte[] convert_to_ascii(byte[] bytes){
        BufferedImage image = btyes_to_bufferedImage(bytes);
        String txt_file = "";   

        image = grayscale_conversion(image);
        image = resize(image);
        txt_file = convert(image);
        
        byte[] file_bytes = txt_file.getBytes(StandardCharsets.UTF_8);
        return file_bytes;
    }







    private static BufferedImage btyes_to_bufferedImage(byte[] bytes){
        String base64Data = new String(bytes, StandardCharsets.US_ASCII);
        System.out.println("[IMAGE_TO_ASCII] base64 length=" + base64Data.length() + " first10=" + base64Data.substring(0, Math.min(10, base64Data.length())));
        
        byte[] imageBytes = Base64.getDecoder().decode(bytes);

        try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)){
            return ImageIO.read(bis);
        } catch (Exception e) {
            System.out.println(e);
            return null;
        }
    }

    private static BufferedImage grayscale_conversion(BufferedImage image){
        for(int j = 0; j < image.getWidth(); j++){
            for(int k = 0; k < image.getHeight(); k++){
                int rgb = image.getRGB(j, k);
                Color c = new Color(rgb, false);
                int gray = (int)( 0.299 * c.getRed()
                                + 0.587 * c.getGreen()
                                + 0.114 * c.getBlue());

                c = new Color(gray, gray, gray);
                image.setRGB(j, k, c.getRGB());
            }

        }
        return image;
    }
      
    private static BufferedImage resize(BufferedImage image){
        double horizontal_scalar = ((double)(image.getHeight()) / ((double)ASCII_HEIGHT));
        int width = (int)(((double)image.getWidth() / horizontal_scalar) * 1.95);

        BufferedImage resizedImage = new BufferedImage(width,
                                                        ASCII_HEIGHT,
                                                        BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resizedImage.createGraphics();
        g.drawImage(image, 0, 0, resizedImage.getWidth(), resizedImage.getHeight(), null);

        return resizedImage;
    }

    private static String convert(BufferedImage image){
        String output = "";
        for(int j = 0; j < image.getHeight(); j ++){
            for(int i = 0; i < image.getWidth(); i++){
                Color c = new Color(image.getRGB(i, j));
                int shade = c.getBlue();

                if(shade < 52){
                    output += ".";
                } else if(shade < 70){
                    output += "*";
                } else if(shade < 90){
                    output += "+";
                } else if(shade < 110){
                    output += "=";
                } else if(shade < 190){
                    output += "%";
                } else if(shade <= 255){
                    output += "#";
                }
                
            }
            output += "\n";
        }
        return output;
    }

}
