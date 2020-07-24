module gomint.jraknet {

    requires io.netty.transport;
    requires io.netty.buffer;
    requires io.netty.transport.epoll;
    requires io.netty.common;
    requires org.slf4j;

    exports io.gomint.jraknet;
}