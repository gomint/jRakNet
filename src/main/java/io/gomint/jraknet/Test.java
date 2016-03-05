package io.gomint.jraknet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class Test implements SocketEventHandler {

	public static void main( String[] args ) {
		new Test();
	}

	public Test() {
		ClientConnection client = new ClientConnection();
		try {
			client.initialize();
		} catch ( SocketException e ) {
			e.printStackTrace();
		}

		client.setEventHandler( this );

		while ( true ) {
			client.pingUnconnected( "127.0.0.1", 19132 );
			try {
				Thread.sleep( 1000 );
			} catch ( InterruptedException e ) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void onSocketEvent( Socket socket, SocketEvent event ) {
		switch ( event.getType() ) {
			case UNCONNECTED_PONG:
				SocketEvent.PingPongInfo ping = event.getPingPongInfo();
				System.out.println( "Received unconnected pong: " + ping.getMotd() );
				break;
			case CONNECTION_ATTEMPT_FAILED:
				System.out.println( "Connection attempt failed: " + event.getReason() );
				break;
			case CONNECTION_ATTEMPT_SUCCEEDED:
				System.out.println( "Connection attempt succeeded" );
				break;
		}
	}
}
