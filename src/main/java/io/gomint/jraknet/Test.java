package io.gomint.jraknet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class Test implements SocketEventHandler {

	public static void main( String[] args ) {
		new Test();
	}

	private AtomicBoolean successPing = new AtomicBoolean( false );

	public Test() {
		System.out.println( (byte) 0x15 );

		ClientConnection client = new ClientConnection();
		try {
			client.initialize();
		} catch ( SocketException e ) {
			e.printStackTrace();
		}

		client.setEventHandler( this );

		while ( !successPing.get() ) {
			client.pingUnconnected( "127.0.0.1", 19132 );

			try {
				Thread.sleep( 1000 );
			} catch ( InterruptedException e ) {
				e.printStackTrace();
			}
		}

		if ( successPing.get() ) {
			client.connect( "127.0.0.1", 19132 );
		}
	}

	@Override
	public void onSocketEvent( Socket socket, SocketEvent event ) {
		switch ( event.getType() ) {
			case UNCONNECTED_PONG:
				SocketEvent.PingPongInfo ping = event.getPingPongInfo();
				System.out.println( "Received unconnected pong: " + ping.getMotd() );
				successPing.set( true );
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
