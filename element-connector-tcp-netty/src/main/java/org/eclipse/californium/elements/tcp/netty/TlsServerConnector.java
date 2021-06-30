/*******************************************************************************
 * Copyright (c) 2016, 2017 Amazon Web Services and others.
 * <p>
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * <p>
 * The Eclipse Public License is available at
 * http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.html.
 * <p>
 * Contributors:
 * Joe Magerramov (Amazon Web Services) - CoAP over TCP support.
 * Achim Kraus (Bosch Software Innovations GmbH) - create "remote aware" SSLEngine
 * Achim Kraus (Bosch Software Innovations GmbH) - introduce protocol,
 *                                                 remove scheme
 * Achim Kraus (Bosch Software Innovations GmbH) - add client authentication mode.
 * Bosch Software Innovations GmbH - migrate to SLF4J
 * Achim Kraus (Bosch Software Innovations GmbH) - add handshake timeout
 ******************************************************************************/
package org.eclipse.californium.elements.tcp.netty;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.eclipse.californium.elements.config.CertificateAuthenticationMode;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.TcpConfig;
import org.eclipse.californium.elements.util.StringUtil;

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;

/**
 * A TLS server connector that accepts inbound TLS connections.
 */
public class TlsServerConnector extends TcpServerConnector {

	/**
	 * Client authentication mode.
	 */
	private final CertificateAuthenticationMode clientAuthMode;
	/**
	 * SSL context.
	 */
	private final SSLContext sslContext;
	/**
	 * Handshake timeout in milliseconds.
	 */
	private final long handshakeTimeoutMillis;

	/**
	 * Initializes SSLEngine with specified SSL engine, client authentication
	 * mode, and handshake timeout.
	 * 
	 * @param sslContext ssl context.
	 * @param socketAddress local server socket address
	 * @param configuration configuration with {@link TcpConfig} definitions.
	 */
	public TlsServerConnector(SSLContext sslContext, InetSocketAddress socketAddress, Configuration configuration) {
		super(socketAddress, configuration, new TlsContextUtil(configuration.get(TcpConfig.TLS_CIENT_AUTH_MODE)));
		this.sslContext = sslContext;
		this.clientAuthMode = configuration.get(TcpConfig.TLS_CIENT_AUTH_MODE);
		this.handshakeTimeoutMillis = configuration.get(TcpConfig.TLS_HANDSHAKE_TIMEOUT, TimeUnit.MILLISECONDS);
	}

	@Override
	protected void onNewChannelCreated(Channel ch) {
		SSLEngine sslEngine = createSllEngineForChannel(ch);
		switch (clientAuthMode) {
		case NONE:
			break;
		case WANTED:
			sslEngine.setWantClientAuth(true);
			break;
		case NEEDED:
			sslEngine.setNeedClientAuth(true);
			break;
		}
		sslEngine.setUseClientMode(false);
		SslHandler sslHandler = new SslHandler(sslEngine);
		sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
		ch.pipeline().addFirst(sslHandler);
	}

	@Override
	public String getProtocol() {
		return "TLS";
	}

	/**
	 * Create SSL engine for channel.
	 * 
	 * @param ch channel to determine remote host
	 * @return created SSL engine
	 */
	private SSLEngine createSllEngineForChannel(Channel ch) {
		SocketAddress remoteAddress = ch.remoteAddress();
		if (remoteAddress instanceof InetSocketAddress) {
			LOGGER.info("Connection from inet {}", StringUtil.toLog(remoteAddress));
			InetSocketAddress remote = (InetSocketAddress) remoteAddress;
			return sslContext.createSSLEngine(remote.getAddress().getHostAddress(), remote.getPort());
		} else {
			LOGGER.info("Connection from {}", StringUtil.toLog(remoteAddress));
			return sslContext.createSSLEngine();
		}
	}

}
