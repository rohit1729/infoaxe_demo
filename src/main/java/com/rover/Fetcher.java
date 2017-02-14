package com.rover;

import com.crispy.log.Appender;
import com.crispy.log.Log;
import com.crispy.net.Http;
import com.crispy.net.Post;
import com.crispy.net.Response;
import com.crispy.server.*;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by rohitgupta on 2/7/17.
 */
@WebServlet(urlPatterns = {"/demo","/demo/*"}, loadOnStartup = 1)
public class Fetcher extends Servlet{
    static HashMap<String,String> id_domain= new HashMap<String, String>();
    static HashMap<Integer,JSONObject> id_image = new HashMap<Integer, JSONObject>();
    static JSONArray input_json = new JSONArray();
    static Log LOG =  Log.get("Fetcher");
    private static String prediction_path = "data/prediction.txt";
    private static String truth_path = "data/truth.txt";
    private ConcurrentHashMap<Integer,JSONObject> predictor_buffer= new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer,JSONObject> truth_buffer = new ConcurrentHashMap<>();
    private Http mHttp;
    private static final int chunk_size = 10;
    private CountDownLatch num_imput;
    private ExecutorService caller = Executors.newFixedThreadPool(5);
    private ExecutorService balancer = Executors.newSingleThreadExecutor();
    private AtomicBoolean balancer_running = new AtomicBoolean();
    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        try {
             LOG.appender(Appender.create("demo_log"));
            balancer_running.set(false);
            Util.inflateIdDomain();
            Util.inflateIdImage();
            Util.inflateInputJson();
            mHttp = Http.builder().build();
        }catch (IOException e){
            LOG.error("Failed to inflate domain or image details");
            e.printStackTrace();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(){
            @Override
            public void run() {
                caller.shutdown();
                balancer.shutdown();
            }
        });
    }



    @GetMethod(path = "/fetch", template = "index.html")
    public JSONObject fetchRecommededAds(@Param("widget_id") String widget_id,
                                        @Param("url") String url,
                                        @Param("userid") String user_id,
                                        @Param("adstyle") String adstyle,@Param("size") Integer size,
                                        HttpServletRequest request,
                                        HttpServletResponse response) throws IOException {
        if (widget_id == null){
            response.sendError(422,"widget id not found");
            return null;
        }
        if(url == null){
            response.sendError(422,"url not found");
            return null;
        }
        if(user_id == null){
            response.sendError(422,"user id not found");
            return null;
        }
        if (id_domain.get(widget_id) == null){
            response.sendError(422,"associated domain not found with widget_id: "+widget_id);
            return null;
        }
        if (size ==null || size == 0){
            size = 12;
        }
        String ip = Util.getIp(request);
        String user_agent = request.getHeader("User-Agent");
        String domain = id_domain.get(widget_id);
        JSONArray content_ids = fetchRecommendedContent(url,widget_id, adstyle, user_id,ip,domain,user_agent,null,null);
        JSONArray top_contents = new JSONArray();
        for (int i=0; i < content_ids.length(); ++i){
            if (id_image.get(content_ids.getJSONObject(i).getInt("content_id")) != null){
                top_contents.put(id_image.get(content_ids.getJSONObject(i).getInt("content_id")));
            }
            if (top_contents.length() >= size) break;
        }
        return new JSONObject().put("contents",top_contents);
    }

    @GetMethod(path = "/build")
    public JSONObject predictor_truth_compare(@Param("inflate") String inflate,@Param("force_stop") Boolean force_stop){
        if (inflate != null && inflate.equalsIgnoreCase("true")){
            if (balancer_running.get()){

                // This force stop implementation is very raw don't use it for now
                if (force_stop){
                    balancer.shutdownNow();
                    caller.shutdownNow();
                    caller = Executors.newFixedThreadPool(5);
                    balancer = Executors.newFixedThreadPool(1);
                    predictor_buffer.clear();
                    truth_buffer.clear();
                    LOG.info("Force stopping balancer and caller");
                }else {
                    return new JSONObject().put("message","already building comparison send force_stop=1 to stop that");
                }
            }
            Util.deleteFile(prediction_path);
            Util.deleteFile(truth_path);
            int offset= 0;
            while(offset < input_json.length()) {
                int size = Math.min(input_json.length() - offset,chunk_size);
                balancer_running.set(true);
                balancer.execute(new Enqueuer(size,offset));
                offset = offset + size;
            }
            return new JSONObject().put("message","building comparison");
        }else {
            if (num_imput != null && num_imput.getCount() > 0){
                return new JSONObject().put("message","already building comparison");
            }else {
                return new JSONObject().put("message","comparison files inflated check /measure_score url");
            }
        }


    }

    @GetMethod(path = "/score" , template = "scores.html")
    public JSONObject buildScore(HttpServletResponse  response, @Param("offset") Integer offset, @Param("size") Integer size) throws IOException{
        if (offset == null || offset == 0) offset = 0;
        if (size == null || size == 0) size = input_json.length();
        ArrayList<String> predictions = Util.fileContent(prediction_path,offset,size);
        JSONArray score_array = new JSONArray();
        HashMap<Integer,ArrayList<Integer>> prediction_results= new HashMap<>();
        ArrayList<String> truths = Util.fileContent(truth_path,offset,size);
        if (predictions == null || truths == null || predictions.size() == 0 || truths.size() == 0){
            response.sendError(404,"prediction or truth file not found");
            return null;
        }
        for (int j = 0; j < predictions.size() ;++j){
            String prediction = predictions.get(j);
            ArrayList<Integer> prediction_ads= new ArrayList<Integer>();
            JSONObject prediction_json = new JSONObject(prediction);
            for (int i = 0; i < prediction_json.getJSONArray("content_ids").length();++i){
                if (prediction_json.getJSONArray("content_ids").getInt(i) > 0) {
                    prediction_ads.add(prediction_json.getJSONArray("content_ids").getInt(i));
                }
            }
            prediction_results.put(prediction_json.getInt("index"),prediction_ads);
        }

        for (int j = 0; j < truths.size() ;++j){
            String truth = truths.get(j);
            ArrayList<Integer> truth_ads = new ArrayList<Integer>();
            JSONObject truth_json = new JSONObject(truth);
            Integer diff = 0;
            for (int i = 0; i < truth_json.getJSONArray("content_ids").length();++i){
                if (truth_json.getJSONArray("content_ids").getInt(i) > 0) {
                    truth_ads.add(truth_json.getJSONArray("content_ids").getInt(i));
                }
            }

            if ((truth_ads.size() == 0 || truth_ads.get(0) == -1) ||
                    (prediction_results.get(truth_json.getInt("index")).size() == 0 || prediction_results.get(truth_json.getInt("index")).get(0) == -1)){
                score_array.put(new JSONObject().put("index",truth_json.getInt("index"))
                        .put("truth_percent","NA")
                        .put("match" ,"NA"));
            }else {
                score_array.put(new JSONObject().put("index",truth_json.getInt("index"))
                        .put("truth_percent",(float) Util.truthContains(truth_ads,prediction_results.get(truth_json.getInt("index")))/truth_ads.size())
                        .put("match" ,String.valueOf(Util.sameOrder(truth_ads,prediction_results.get(truth_json.getInt("index"))))));
            }

        }
        LOG.info("score: "+score_array.toString());
        return new JSONObject().put("scores",score_array);
    }



    private JSONArray fetchRecommendedContent(String url, String widget_id, String adstyle,
                                              String user_id, String ip, String domain,
                                              String user_agent,String geo,String device) {
        String end_point = "http://test-1776469641.eu-central-1.elb.amazonaws.com/ml/ads";
        Post post = Post.builder(end_point).addHeader("Content-Type", "application/json")
                .addHeader("Cache-Control","no-cache")
                .setBody(new JSONObject()
                        .put("url",url)
                        .put("widget_id",widget_id)
                        .put("userid",user_id)
                        .put("device",device != null ? device: "desktoplg")
                        .put("ip",ip)
                        .put("useragent",user_agent)
                        .put("adstyle",(adstyle != null ? adstyle:"4x2"))
                        .put("geo",geo != null ? geo : "US")
                        .put("domain",domain)).build();
        Response response = mHttp.execute(post);
        if (response == null || response.status() != 200){
            LOG.warn("Request failed for widget_id: "+widget_id);
        }else {
            return response.toJSONObject().getJSONArray("ads");
        }
        return null;
    }

    private JSONArray getPredictedContent(JSONObject data){
        Post post = Post.builder("http://ec2-52-207-175-94.compute-1.amazonaws.com:8005/predictortest/sample")
                .addParam("json",data.toString()).build();
        Response response = mHttp.execute(post);
        if (response == null || response.status() != 200){
            LOG.warn("Request failed for widget_id: "+ data.get("widget_id"));
        }else {
            return response.toJSONObject().getJSONArray("ad");
        }
        return null;
    }

    public void fetch_parse_recommendation(JSONObject jsonObject, int index) {
        LOG.info("fetch_parse_recommedation");
        JSONArray response = fetchRecommendedContent(jsonObject.getString("landing_url"),
                String.valueOf(jsonObject.getInt("widget_id")),
                jsonObject.getJSONArray("payload").getJSONObject(0).getString("ad_style"),
                jsonObject.getString("uniq_id"),
                jsonObject.getString("user_ip"),
                jsonObject.getString("domain"),
                jsonObject.getString("user_agent"),
                jsonObject.getString("country"),
                jsonObject.getString("resolution"));
        JSONArray content_ids = new JSONArray();
        if (response != null) {
            for (int j = 0; j < response.length(); ++j) {
                if (j >=1 && response.getJSONObject(j-1).getInt("content_id") != response.getJSONObject(j).getInt("content_id")) {
                    content_ids.put(response.getJSONObject(j).getInt("content_id"));
                }
            }
            truth_buffer.put(index,new JSONObject().put("index", index).put("content_ids", content_ids));
        } else {
            LOG.warn("failed truth call for widget_id: " +
                    jsonObject.getInt("widget_id") + " index: " + index);

            truth_buffer.put(index,new JSONObject().put("index", index)
                    .put("content_ids", content_ids.put(-1)));
        }
    }

    public void fetch_parse_prediction(JSONObject data,int index){
        LOG.info("fetch_parse_prediction");
        JSONArray response = getPredictedContent(data);
        JSONArray content_ids = new JSONArray();
        if (response != null) {
            for (int j = 0; j < response.length(); ++j) {
                if (j >=1 && response.getJSONObject(j-1).getInt("content_id") != response.getJSONObject(j).getInt("content_id")){
                    content_ids.put(response.getJSONObject(j).getInt("content_id"));
                }
            }
            predictor_buffer.put(index, new JSONObject().put("index",index)
                    .put("content_ids", content_ids));
        }else {
            LOG.warn("failed prediction call for widget_id: "
                    +data.getInt("widget_id") + " index: "+index);

            predictor_buffer.put(index,new JSONObject().put("index", index)
                    .put("content_ids", content_ids.put(-1)));
        }
    }



    class Enqueuer implements Runnable{
        int start_index ,size;
        public Enqueuer(int chunk_size,int start_index) {
            LOG.info("Enqueuer inflated with chunk size: "+chunk_size+"and start_index: "+start_index);
            this.start_index = start_index;
            this.size = chunk_size;
        }

        @Override
        public void run() {
            num_imput = new CountDownLatch(size);
            for (int i = start_index; i < start_index+size; ++i) {
                caller.execute(new ChunkRunnable(i, input_json.getJSONObject(i)));
            }
            try{
                num_imput.await();
                dumpToFile();
                if (start_index+chunk_size >= input_json.length()){
                    balancer_running.set(false);
                }
                LOG.info("Chunkable calls completed inputline: "+start_index+"size: "+size);
            }catch (InterruptedException e){
                e.printStackTrace();
            }

        }

        private void dumpToFile(){
            JSONObject[] content = new JSONObject[chunk_size];
            int offset = -1;
            for (Integer i:predictor_buffer.keySet()){
                if (offset == -1){
                    offset = (i/chunk_size) * chunk_size;
                }
                content[i - offset] = predictor_buffer.get(i);
            }
            Util.writeToFile(prediction_path,content);

            content = new JSONObject[chunk_size];
            offset = -1;
            for (Integer i:truth_buffer.keySet()){
                if (offset == -1){
                    offset = (i/chunk_size) * chunk_size;
                }
                content[i - offset] = truth_buffer.get(i);
            }
            Util.writeToFile(truth_path,content);
            predictor_buffer.clear();
            truth_buffer.clear();
        }
    }

    class ChunkRunnable implements Runnable{
        JSONObject jsonObject;
        int index;
        public ChunkRunnable(int index,JSONObject jsonObject){
            this.jsonObject = jsonObject;
            this.index = index;
        }

        @Override
        public void run() {
            LOG.info(index+" : Chunkable running");
            fetch_parse_prediction(jsonObject,index);
            fetch_parse_recommendation(jsonObject,index);
            num_imput.countDown();
        }
    }


}
