package io.gomint.jraknet;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

/**
 * @author geNAZt
 * @version 1.0
 */
public class EventLoops {

    public static EventLoopGroup LOOP_GROUP;

    static {
        EventLoops.LOOP_GROUP = Epoll.isAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
    }

}
