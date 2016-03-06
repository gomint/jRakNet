package io.gomint.jraknet;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class Test implements SocketEventHandler {

	public static void main( String[] args ) {
		new Test();
	}

	public Test() {
		/*
		ServerSocket server = new ServerSocket( 10 );
		try {
			server.bind( "127.0.0.1", 19132 );
		} catch ( SocketException e ) {
			e.printStackTrace();
		}

		while ( true ) {
			;
		}
		*/

		ClientConnection client = new ClientConnection();
		try {
			client.initialize();
		} catch ( SocketException e ) {
			e.printStackTrace();
		}

		client.setEventHandler( this );

		while ( true ) {
			client.pingUnconnected( "255.255.255.255", 19132 );
			try {
				Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
				while ( interfaces.hasMoreElements() ) {
					NetworkInterface networkInterface = interfaces.nextElement();

					if ( !networkInterface.isUp() ) {
						continue;
					}

					for ( InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses() ) {
						InetAddress broadcast = interfaceAddress.getBroadcast();
						if ( broadcast == null ) {
							continue;
						}

						client.pingUnconnected( broadcast, 19132 );
					}
				}
			} catch ( SocketException e ) {
				e.printStackTrace();
			}
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
