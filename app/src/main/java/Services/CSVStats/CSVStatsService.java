package Services.CSVStats;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import org.apache.commons.math3.stat.StatUtils;
import java.util.Arrays;

public class CSVStatsService {

    public static byte[] get_csv_stats(byte[] data){
        ArrayList<ArrayList<String>> input_file = UTF_to_CSV_list(data);
        data = null;
        ArrayList<ArrayList<String>> output_file = find_stats(input_file);
        input_file = null;
        String output_string = create_csv_string(output_file);
        output_file = null;
        byte[] file_bytes = output_string.getBytes(StandardCharsets.UTF_8);
        output_file = null;
        return Base64.getEncoder().encode(file_bytes);  
    }


    private static ArrayList<ArrayList<String>> find_stats(ArrayList<ArrayList<String>> data) {
        ArrayList<ArrayList<String>> results = new ArrayList<>();
        ArrayList<ArrayList<String>> output = new ArrayList<>();
        ArrayList<String> column_titles = new ArrayList();
        

        double[] col = new double[data.size() - 1]; // exclude header row

        // Iterate over each column
        for (int column = 0; column < data.get(0).size(); column++) {
        
            boolean is_numeric = true;
            int parsed_count = 0;
        
            for (int row = 1; row < data.size(); row++) {
                String cell = data.get(row).get(column).trim();
                try {
                    col[row - 1] = Double.parseDouble(cell);
                    parsed_count++;
                } catch (NumberFormatException e) {
                    is_numeric = false;
                    break;
                }
            }
            if (!is_numeric || parsed_count == 0) continue;
        
            double[] filled = Arrays.copyOf(col, parsed_count);
            Arrays.sort(filled);
            column_titles.add(data.get(0).get(column));
        
            ArrayList<String> stat_column = new ArrayList<>();
            stat_column.add(String.valueOf(StatUtils.mean(filled)));
            stat_column.add(String.valueOf(filled.length % 2 == 1 
                ? filled[filled.length/2] 
                : (filled[filled.length/2 - 1] + filled[filled.length/2]) / 2.0));
            stat_column.add(String.valueOf(StatUtils.mode(filled)[0]));
            stat_column.add(String.valueOf(Math.sqrt(StatUtils.variance(filled))));
            stat_column.add(String.valueOf(StatUtils.min(filled)));
            stat_column.add(String.valueOf(StatUtils.max(filled)));
            
            results.add(stat_column);
            col = new double[data.size() - 1]; // reset for next column
        }

        
        output = transpose(results);
        for(int i = 1; i < output.get(0).size(); i++){
            output.get(0).set(i, column_titles.get(i-1));
        }

        output.get(1).set(0, "mean");
        output.get(2).set(0, "median");
        output.get(3).set(0, "mode");
        output.get(4).set(0, "standard deviation");
        output.get(5).set(0, "min");
        output.get(6).set(0, "max");


        return output;
    }


    public static ArrayList<ArrayList<String>> UTF_to_CSV_list(byte[] file_bytes){
        byte[] decoded_bytes = Base64.getDecoder().decode(file_bytes);
        String csv_text = new String(decoded_bytes, StandardCharsets.UTF_8);
    
        ArrayList<ArrayList<String>> data = new ArrayList<>();
    
        try {
            BufferedReader reader = new BufferedReader(new StringReader(csv_text));
                String line;
                while ((line = reader.readLine()) != null) {
                    data.add(parse_line(line));
                }
            } catch (IOException e){
                System.out.println("Failed to parse csv String" + e);
            }
        return data;
    }
    

    private static String create_csv_string(ArrayList<ArrayList<String>> csv_list){
        String output = "";

        for(int i = 0; i < csv_list.size(); i ++){
            for(int j = 0; j < csv_list.get(i).size(); j++){
                output += csv_list.get(i).get(j);
                output += ",";
            }
            output += "\n";
        }

        return output;
    }



    private static ArrayList<String> parse_line(String line){
        ArrayList<String> result = new ArrayList<>();
        boolean in_quotes = false;
        String value = "";

        for(int i = 0; i < line.length(); i++ ){
            char c = line.charAt(i);

            if(c == '"') {
                in_quotes = !in_quotes;
            } else if (c == ',' && !in_quotes){
                result.add(value);
                value = "";
            } else {
                value += c;
            }
        }
        result.add(value);

        return result;   
    }


    public static ArrayList<ArrayList<String>> transpose(ArrayList<ArrayList<String>> input) {
        ArrayList<ArrayList<String>> result = new ArrayList<>();

        if (input == null || input.isEmpty()) {
            return result;
        }

        int numRows = input.size();
        int numCols = input.get(0).size();

        // Add blank row at index 0 (length = numRows + 1 to account for blank col at index 0)
        ArrayList<String> blankRow = new ArrayList<>();
        for (int i = 0; i <= numRows; i++) {
            blankRow.add("");
        }
        result.add(blankRow);

        for (int col = 0; col < numCols; col++) {
            ArrayList<String> newRow = new ArrayList<>();
            // Add blank cell at column 0
            newRow.add("");
            for (int row = 0; row < numRows; row++) {
                newRow.add(input.get(row).get(col));
            }
            result.add(newRow);
        }

        return result;
    }


    /*
    -----------------TESTING ONLY-------------------
    The following functions are set up for testing this in a controlled environment
    */
    public static byte[] test_byte_generator(String csv_file){
        byte[] utf8 = csv_file.getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encode(utf8); // returns ASCII bytes of base64 text
    }

    private static void printArray(ArrayList<ArrayList<String>> list){
        for(int i = 0; i < list.size(); i++){
            for(int j = 0; j < list.get(i).size(); j++){
                System.out.print(list.get(i).get(j) + " ");
            }
            System.out.println("");
        }
    }


    private static void print1dArray(ArrayList<String> list){
        for(int i = 0; i < list.size(); i++){
            System.out.print(list.get(i) + " ");
        }
        System.out.println("");
    }

    
}
