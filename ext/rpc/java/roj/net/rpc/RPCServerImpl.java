package roj.net.rpc;

import roj.concurrent.Executor;
import roj.net.MyChannel;
import roj.net.rpc.api.RPCClient;
import roj.net.rpc.api.RPCServer;
import roj.net.rpc.api.RemoteProcedure;

import java.io.IOException;

/**
 * @author Roj234
 * @since 2025/10/13 1:24
 */
public class RPCServerImpl implements RPCServer, RPCClient {
	public RPCServerImpl(Executor executor) {

	}

	public void attachTo(MyChannel channel) {

	}

	@Override
	public <T extends RemoteProcedure> T getImplementation(Class<T> type) throws RemoteException {
		return null;
	}

	@Override
	public void close() throws IOException {

	}

	@Override
	public <T extends RemoteProcedure> void registerImplementation(Class<T> type, T impl) {

	}
}
