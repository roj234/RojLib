package roj.net.rpc;

import roj.ci.annotation.Public;
import roj.concurrent.Promise;
import roj.net.ChannelHandler;
import roj.net.MyChannel;
import roj.net.rpc.api.RPCClient;
import roj.net.rpc.api.RemoteProcedure;

import java.io.IOException;

/**
 * @author Roj234
 * @since 2025/10/11 22:47
 */
@Public
public class RPCClientImpl implements RPCClient, ChannelHandler {

	@Override
	public <T extends RemoteProcedure> T getImplementation(Class<T> type) throws RemoteException {
		return null;
	}

	@Override
	public void close() throws IOException {

	}

	public void attachTo(MyChannel channel) {
		// TODO use generic HTTP RPC
	}

	public Promise<RPCClient> onOpened() {
		return null;
	}
}
