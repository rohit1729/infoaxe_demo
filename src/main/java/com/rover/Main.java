package com.rover;

import com.crispy.server.Server;
import com.crispy.template.Template;

import java.io.File;

/**
 * Created by rohitgupta on 2/7/17.
 */
public class Main {
    public static void main(String[] args) throws Exception{
        Template.setMacroDirectory(new File("templates"));
        Server server = new Server(null,8000);
        server.setBaseDir("src/main/webapp");
        server.addServlet(Fetcher.class);
        server.start();
    }


}
