package io.gomint.jraknet;

import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author geNAZt
 * @version 1.0
 */
public class TestRunner {

    public static void main( String[] args ) {
        // Create a server socket
        ServerSocket serverSocket = new ServerSocket( 500 );
        serverSocket.setMojangModificationEnabled( true );

        try {
            serverSocket.bind( "127.0.0.1", 1337 );
        } catch ( SocketException e ) {
            e.printStackTrace();
        }

        // Spawn ~120 connections
        ScheduledExecutorService service = Executors.newScheduledThreadPool( 128 );
        for ( int i = 0; i < 500; i++ ) {
            createClient( service );
        }
    }

    private static void createClient( ScheduledExecutorService service ) {
        ClientSocket socket = new ClientSocket();
        socket.setMojangModificationEnabled( true );

        try {
            socket.initialize();
        } catch ( SocketException e ) {
            e.printStackTrace();
        }

        socket.connect( "127.0.0.1", 1337 );

        service.scheduleAtFixedRate( new Runnable() {
            @Override
            public void run() {
                socket.getConnection().send( new byte[37] );
            }
        }, 50, 50, TimeUnit.MILLISECONDS );
    }

}
