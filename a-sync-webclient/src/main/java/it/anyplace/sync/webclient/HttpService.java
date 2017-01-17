/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package it.anyplace.sync.webclient;

import it.anyplace.sync.client.SyncthingClient;
import it.anyplace.sync.core.configuration.ConfigurationService;
import java.io.Closeable;
import java.io.IOException;
import java.util.logging.Level;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpService implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final ConfigurationService configuration;
    private final SyncthingClient syncthingClient;
    private final static int PORT = 8385;
    private final Server server;

    public HttpService(ConfigurationService configuration) {
        this.configuration = configuration;
        this.syncthingClient = new SyncthingClient(configuration);
        this.server = new Server(PORT);

        HandlerList handlerList = new HandlerList();
        {
            ContextHandler contextHandler = new ContextHandler("/api");
            contextHandler.setHandler(new AbstractHandler() {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                    logger.info("ws!!");

                    new Thread() {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(200);
                            } catch (InterruptedException ex) {
                            }
                            close();
                        }
                    }.start();

                    response.setContentType("application/json");
                    response.getWriter().write("{success:true}");
                }
            });
            handlerList.addHandler(contextHandler);
        }
        {
            ResourceHandler resourceHandler = new ResourceHandler();
            resourceHandler.setBaseResource(Resource.newClassPathResource("/web"));
            ContextHandler contextHandler = new ContextHandler("/web");
            contextHandler.setHandler(resourceHandler);
            handlerList.addHandler(contextHandler);
        }
        server.setHandler(handlerList);
    }

    @Override
    public void close() {
        try {
            if (server.isRunning()) {
                server.stop();
            }
        } catch (Exception ex) {
            logger.error("error stopping jetty server", ex);
        }
        IOUtils.closeQuietly(syncthingClient);
    }

    public void start() throws Exception {
        logger.info("starting jetty server");
        server.start();
    }

    public void join() throws Exception {
        server.join();
    }

    public int getPort() {
        return PORT;
    }

}
