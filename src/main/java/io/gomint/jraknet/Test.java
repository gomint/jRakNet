package io.gomint.jraknet;

import java.net.SocketException;

/**
 * @author BlackyPaw
 * @version 1.0
 */
public class Test {

	public static void main( String[] args ) {
		ServerSocket socket = new ServerSocket( 10 );
		try {
			socket.bind( "127.0.0.1", 19132 );
		} catch ( SocketException e ) {
			e.printStackTrace();
		}

		socket.setMotd( "WADDAFUQ" );

		while ( true ) {
			;
		}
	}
}
