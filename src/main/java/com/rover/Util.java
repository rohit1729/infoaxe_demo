package com.rover;

import com.crispy.log.Log;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * Created by rohitgupta on 2/7/17.
 */
public class Util {
    static Log LOG = Log.get("Utils");
    public static String getIp(HttpServletRequest request){
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (ip == null || ip.length() == 0 || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    public static void inflateIdDomain() throws IOException{
        LOG.debug("inflating widget id --> domain mapping");
        File f = new File("data/publisher_performance_manage_widgets.csv");
        FileReader fr = new FileReader(f);
        BufferedReader bfr = new BufferedReader(fr,1024);
        String line;
        while ((line = bfr.readLine()) != null){
            String[] parts = line.split(",");
            if (parts.length >= 5 && stripcommas(parts[0]) != null && stripcommas(parts[4]) != null) {
                Fetcher.id_domain.put(stripcommas(parts[0]), stripcommas(parts[4]));
            }
        }
    }

    public static void inflateIdImage() throws IOException{
        LOG.debug("inflating content id --> details mapping");
        File f = new File("data/admin_performance_content_sponsored.csv");
        FileReader fr = new FileReader(f);
        BufferedReader bfr = new BufferedReader(fr,1024);
        String line;
        while((line = bfr.readLine()) != null){
            String[] parts = line.split("\",");
            JSONObject jsonObject = new JSONObject();
            if (parts.length >= 25 && parts[0] != null && parts[1] != null && parts[2] != null && parts[24] != null){
                jsonObject.put("id",stripcommas(parts[0]));
                jsonObject.put("title",stripcommas(parts[1]));
                jsonObject.put("target_url",stripcommas(parts[2]));
                jsonObject.put("image_url",stripcommas(parts[24]));
                try {
                    Fetcher.id_image.put(Integer.valueOf(stripcommas(parts[0])),jsonObject);
                }catch (NumberFormatException e){
                    LOG.warn("Content id cannot be casted to integer: "+stripcommas(parts[0]));
                }
            }
        }
    }

    public static void inflateInputJson() throws IOException{
        LOG.debug("inflating input json");
        File f = new File("data/examples_1500.json.txt");
        FileReader fr = new FileReader(f);
        BufferedReader br = new BufferedReader(fr);
        String line;
        while ((line = br.readLine()) != null){
            Fetcher.input_json.put(new JSONObject(line));
        }
    }

    public static void writeToFile(String filePath, JSONObject[] data){
        try {
            LOG.debug("writing values to output file: "+filePath);
            File f = new File(filePath);
            FileWriter fw = new FileWriter(f,true);
            BufferedWriter bwr = new BufferedWriter(fw,1024);
            for (int i=0; i < data.length; ++i){
                if (data[i] != null) {
                    bwr.write(data[i].toString());
                    bwr.newLine();
                }
            }
            bwr.flush();
            bwr.close();
        }catch (IOException e){
            LOG.error("writing to file with path: "+filePath+" failed");
        }
    }

    public static ArrayList<String> fileContent(String filePath){
        try {
            ArrayList<String> content = new ArrayList<String>();
            LOG.debug("writing values to output file");
            File f = new File(filePath);
            FileReader fr = new FileReader(f);
            BufferedReader bwr = new BufferedReader(fr,1024);
            String line;
            while((line = bwr.readLine()) != null){
                content.add(line.trim());
            }
            bwr.close();
            return content;
        }catch (IOException e){
            LOG.error("Writing to file with path: "+filePath+" failed");
            return null;
        }
    }

    public static ArrayList<String> fileContent(String filePath, long line_number, long offset){
        try {
            ArrayList<String> content = new ArrayList<String>();
            LOG.debug("writing values to output file");
            File f = new File(filePath);
            FileReader fr = new FileReader(f);
            BufferedReader bwr = new BufferedReader(fr,1024);
            String line;
            int start = 0;
            while((line = bwr.readLine()) != null){
                start = start+1;
                if (start >= line_number && start <= line_number+offset){
                    content.add(line.trim());
                }
                if (start > line_number+offset) break;
            }
            bwr.close();
            return content;
        }catch (IOException e){
            LOG.error("Writing to file with path: "+filePath+" failed");
            return null;
        }
    }

    private static String stripcommas(String input){
        return input.replaceAll("^\"|\"$", "");
    }

    public static boolean exists(String path){
        File f = new File(path);
        return f.exists();
    }

    public static void deleteFile(String filePath){
        LOG.debug("deleting file: "+filePath);
        File f = new File(filePath);
        if (!f.exists()) return;
        if (f.delete()){
            LOG.info("file deleted: "+filePath);
        }else {
            LOG.info("file could not be deleted: "+filePath);
        }
    }

    public static Boolean sameOrder(ArrayList<Integer> truth_list, ArrayList<Integer> prediction_list){
        if (truth_list.size() == 0 || prediction_list.size() == 0){
            return false;
        }else {
            for (int i = 0; i < truth_list.size(); i++){
                if (i >= prediction_list.size()){
                    return  true;
                }
                if (truth_list.get(i) != prediction_list.get(i)){
                    return false;
                }
            }
            return true;
        }
    }

    public static int truthContains(ArrayList<Integer> truth_list, final ArrayList<Integer> prediction_list) {
        final HashSet<Integer> predictions = new HashSet<Integer>();
        for (Integer prediction : prediction_list) {
            predictions.add(prediction);
        }
        Integer match = 0;
        for (Integer truth : truth_list) {
            if (predictions.contains(truth)) {
                match++;
            }
        }
        return match;
    }

}

