/*******************************************************************************
 * Copyright (c) 2015 - 2019 Bosch Software Innovations GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Kai Hudalla (Bosch Software Innovations GmbH) - re-factor DTLSConnectorConfig into
 *                                               an immutable, provide a "builder" for easier
 *                                               instantiation/configuration
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add support for anonymous client-only
 *                                               configuration
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 483559
 *    Achim Kraus (Bosch Software Innovations GmbH) - add enable address reuse
 *    Ludwig Seitz (RISE SICS) - Added support for raw public key validation
 *    Achim Kraus (Bosch Software Innovations GmbH) - include trustedRPKs in
 *                                                    determineCipherSuitesFromConfig
 *    Achim Kraus (Bosch Software Innovations GmbH) - add automatic resumption
 *    Achim Kraus (Bosch Software Innovations GmbH) - issue #549
 *                                                    trustStore := null, disable x.509
 *                                                    trustStore := [], enable x.509, trust all
 *    Bosch Software Innovations GmbH - remove serverNameResolver property
 *    Vikram (University of Rostock) - added CipherSuite TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256
 *    Achim Kraus (Bosch Software Innovations GmbH) - add multiple receiver threads.
 *                                                    move default thread numbers to this configuration.
 *    Achim Kraus (Bosch Software Innovations GmbH) - add deferred processed messages
 *    Achim Kraus (Bosch Software Innovations GmbH) - add server only.
 *******************************************************************************/

package org.eclipse.californium.scandium.config;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.californium.elements.DtlsEndpointContext;
import org.eclipse.californium.elements.util.StringUtil;
import org.eclipse.californium.scandium.ConnectionListener;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.DtlsHealth;
import org.eclipse.californium.scandium.auth.ApplicationLevelInfoSupplier;
import org.eclipse.californium.scandium.dtls.CertificateMessage;
import org.eclipse.californium.scandium.dtls.CertificateRequest;
import org.eclipse.californium.scandium.dtls.CertificateType;
import org.eclipse.californium.scandium.dtls.ConnectionIdGenerator;
import org.eclipse.californium.scandium.dtls.ExtendedMasterSecretMode;
import org.eclipse.californium.scandium.dtls.HelloVerifyRequest;
import org.eclipse.californium.scandium.dtls.InMemoryConnectionStore;
import org.eclipse.californium.scandium.dtls.ProtocolVersion;
import org.eclipse.californium.scandium.dtls.RecordLayer;
import org.eclipse.californium.scandium.dtls.ResumptionSupportingConnectionStore;
import org.eclipse.californium.scandium.dtls.SessionStore;
import org.eclipse.californium.scandium.dtls.SignatureAndHashAlgorithm;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite.KeyExchangeAlgorithm;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuiteSelector;
import org.eclipse.californium.scandium.dtls.cipher.DefaultCipherSuiteSelector;
import org.eclipse.californium.scandium.dtls.cipher.XECDHECryptography.SupportedGroup;
import org.eclipse.californium.scandium.dtls.pskstore.AdvancedPskStore;
import org.eclipse.californium.scandium.dtls.resumption.ConnectionStoreResumptionVerifier;
import org.eclipse.californium.scandium.dtls.resumption.ResumptionVerifier;
import org.eclipse.californium.scandium.dtls.x509.CertificateConfigurationHelper;
import org.eclipse.californium.scandium.dtls.x509.CertificateProvider;
import org.eclipse.californium.scandium.dtls.x509.ConfigurationHelperSetup;
import org.eclipse.californium.scandium.dtls.x509.NewAdvancedCertificateVerifier;
import org.eclipse.californium.scandium.dtls.x509.SingleCertificateProvider;
import org.eclipse.californium.scandium.util.ListUtils;

/**
 * A container for all configuration options of a {@link DTLSConnector}.
 * <p>
 * Instances of this class are immutable and can only be created by means of
 * the {@link Builder}, e.g.
 * </p>
 * <pre>
 * InetSocketAddress bindToAddress = new InetSocketAddress(0); // use ephemeral port
 * DtlsConnectorConfig config = new DtlsConnectorConfig.Builder()
 *    .setAddress(bindToAddress)
 *    .setAdvancedPskStore(new AdvancedSinglePskStore("identity", "secret".getBytes()))
 *    .set... // additional configuration
 *    .build();
 * 
 * DTLSConnector connector = new DTLSConnector(config);
 * connector.start();
 * ...
 * </pre>
 * 
 * Generally the not provided configuration values will be filled in using
 * proper values for the already provided ones. E.g. if the
 * {@link Builder#setAdvancedPskStore(AdvancedPskStore)} is used, but no
 * explicit cipher suite is set with
 * {@link Builder#setSupportedCipherSuites(CipherSuite...)}, the configuration
 * chose some PSK cipher suites on its own. For the asymmetric cryptography
 * functions, the estimation of the proper signature and hash algorithms and the
 * supported curves for ECDSA/ECDHE is more complicated. Therefore this is
 * implemented in the {@link CertificateConfigurationHelper}, see there for
 * details.
 */
public final class DtlsConnectorConfig {

	/**
	 * The default value for the {@link #maxDeferredProcessedOutgoingApplicationDataMessages} property.
	 */
	public static final int DEFAULT_MAX_DEFERRED_PROCESSED_APPLICATION_DATA_MESSAGES = 10;
	/**
	 * The default value for the {@link #maxConnections} property.
	 */
	public static final int DEFAULT_MAX_CONNECTIONS = 150000;
	/**
	 * The default value for the {@link #maxFragmentedHandshakeMessageLength} property.
	 */
	public static final int DEFAULT_MAX_FRAGMENTED_HANDSHAKE_MESSAGE_LENGTH = 8192;
	/**
	 * The default value for the {@link #maxDeferredProcessedIncomingRecordsSize} property.
	 */
	public static final int DEFAULT_MAX_DEFERRED_PROCESSED_INCOMING_RECORDS_SIZE = 8192;
	/**
	 * The default value for the {@link #staleConnectionThreshold} property in seconds.
	 */
	public static final long DEFAULT_STALE_CONNECTION_TRESHOLD = 30 * 60; // 30 minutes
	/**
	 * The default value for the {@link #retransmissionTimeout} property in
	 * milliseconds.
	 * 
	 * @since 3.0 2s instead of 1s (following ACK timeout in
	 *        <a href="https://tools.ietf.org/html/rfc7252#section-4.8" target=
	 *        "_blank">RFC7252</a>).
	 */
	public static final int DEFAULT_RETRANSMISSION_TIMEOUT_MS = 2000;
	/**
	 * The retransmission timeout according
	 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.4.1" target=
	 * "_blank">RFC6347</a>.
	 * 
	 * @since 3.0
	 */
	public static final int RFC6347_RETRANSMISSION_TIMEOUT_MS = 1000;
	/**
	 * The retransmission timeout according
	 * <a href="https://tools.ietf.org/html/rfc7925#section-11" target=
	 * "_blank">RFC7925</a>.
	 * 
	 * @since 3.0
	 */
	public static final int RFC7925_RETRANSMISSION_TIMEOUT_MS = 9000;
	/**
	 * The default value for the {@link #additionalTimeoutForEcc} property in
	 * milliseconds.
	 * 
	 * @since 3.0
	 */
	public static final int DEFAULT_ADDITIONAL_TIMEOUT_FOR_ECC_MS = 0;
	/**
	 * The default value for the {@link #maxRetransmissions} property.
	 */
	public static final int DEFAULT_MAX_RETRANSMISSIONS = 4;
	/**
	 * The default value for the {@link #verifyPeersOnResumptionThreshold}
	 * property in percent.
	 */
	public static final int DEFAULT_VERIFY_PEERS_ON_RESUMPTION_THRESHOLD_IN_PERCENT = 30;
	/**
	 * The default value for the {@link #maxTransmissionUnitLimit} property.
	 * @since 2.3
	 */
	public static final int DEFAULT_MAX_TRANSMISSION_UNIT_LIMIT = RecordLayer.DEFAULT_ETH_MTU;
	/**
	 * The default size of the executor's thread pool which is used for processing records.
	 * <p>
	 * The value of this property is 6 * <em>#(CPU cores)</em>.
	 */
	private static final int DEFAULT_EXECUTOR_THREAD_POOL_SIZE = 6 * Runtime.getRuntime().availableProcessors();
	/**
	 * The default number of receiver threads.
	 * <p>
	 * The value of this property is (<em>#(CPU cores)</em> + 1) / 2.
	 */
	private static final int DEFAULT_RECEIVER_THREADS = (Runtime.getRuntime().availableProcessors() + 1) / 2;

	/**
	 * Local network interface.
	 */
	private InetSocketAddress address;
	/**
	 * Advanced certificate verifier for non-blocking dynamic trust.
	 * @since 2.5
	 */
	private NewAdvancedCertificateVerifier advancedCertificateVerifier;
	/**
	 * Stop retransmission at message receipt
	 */
	private Boolean earlyStopRetransmission;

	/**
	 * Enable to reuse the address.
	 */
	private Boolean enableReuseAddress;

	/**
	 * The record size limit.
	 * 
	 * Included in the CLIENT_HELLO and SERVER_HELLO to negotiate the record
	 * size limit.
	 * 
	 * @since 2.4
	 */
	private Integer recordSizeLimit;

	/**
	 * The maximum fragment length this connector can process at once.
	 */
	private Integer maxFragmentLengthCode;

	/**
	 * The maximum length of a reassembled fragmented handshake message.
	 */
	private Integer maxFragmentedHandshakeMessageLength;

	/**
	 * Enable to use UDP messages with multiple dtls records.
	 * 
	 * @since 2.4
	 */
	private Boolean enableMultiRecordMessages;
	/**
	 * Enable to use dtls records with multiple handshake messages.
	 * 
	 * @since 2.4
	 */
	private Boolean enableMultiHandshakeMessageRecords;
	/**
	 * Protocol version to use for sending a hello verify request. Default
	 * {@code null} to reply the clients version.
	 * 
	 * @since 2.5
	 */
	private ProtocolVersion protocolVersionForHelloVerifyRequests;

	/** The initial timer value for retransmission; rfc6347, section: 4.2.4.1 */
	private Integer retransmissionTimeout;

	/**
	 * The initial additional timer value for retransmission, if ECC
	 * calculations are expected.
	 * 
	 * ECC calculations may be time intensive, especially for smaller
	 * micro-controllers without ecc-hardware support. The additional timeout
	 * prevents Californium from resending a flight too early. The extra time is
	 * used for the DTLS-client, if a ECDSA or ECDHE cipher suite is proposed,
	 * and for the DTLS-server, if a ECDSA or ECDHE cipher suite is selected.
	 * 
	 * @since 3.0
	 */
	private Integer additionalTimeoutForEcc;

	/**
	 * Number of retransmissions before the attempt to transmit a flight in
	 * back-off mode.
	 * 
	 * <a href="https://tools.ietf.org/html/rfc6347#page-12 target="_blank">
	 * RFC6347, Section 4.1.1.1, Page 12</a>
	 * 
	 * In back-off mode, UDP datagrams of maximum 512 bytes are used. Each
	 * handshake message is placed in one dtls record, or more dtls records, if
	 * the handshake message is too large and must be fragmented. Beside of the
	 * CCS and FINISH dtls records, which send together in one UDP datagram, all
	 * other records are send in separate datagrams.
	 * 
	 * The {@link #useMultiHandshakeMessageRecords()} and
	 * {@link #useMultiRecordMessages()} has precedence over the back-off
	 * definition.
	 * 
	 * Value {@code 0}, to disable it, {@code null}, for default of
	 * {@link #maxRetransmissions} / 2.
	 * 
	 * @since 2.4
	 */
	private Integer backOffRetransmission;

	/**
	 * Maximal number of retransmissions before the attempt to transmit a
	 * message is canceled.
	 */
	private Integer maxRetransmissions;

	/**
	 * Maximum transmission unit.
	 */
	private Integer maxTransmissionUnit;

	/**
	 * Maximum transmission unit limit for auto detection. Default
	 * {@value #DEFAULT_MAX_TRANSMISSION_UNIT_LIMIT}
	 * 
	 * @since 2.3
	 */
	private Integer maxTransmissionUnitLimit;

	/**
	 * Does the server want/request the client to authenticate, when x509/RPK is used.
	 */
	private Boolean clientAuthenticationWanted;

	/**
	 * Does the server require the client to authenticate, when x509/RPK is used.
	 */
	private Boolean clientAuthenticationRequired;

	/** does not start handshakes at all. Ignore handshake modes! */
	private Boolean serverOnly;

	/** Default handshake mode. */
	private String defaultHandshakeMode;

	/**
	 * Advanced store of PSK credentials.
	 * 
	 * @since 2.3
	 */
	private AdvancedPskStore advancedPskStore;

	/**
	 * The certificate identity provider.
	 * 
	 * @since 3.0
	 */
	private CertificateProvider certificateIdentityProvider;
	/**
	 * The certificate configuration helper.
	 * 
	 * @since 3.0
	 */
	private CertificateConfigurationHelper certificateConfigurationHelper;
	/**
	 * Cipher suite selector.
	 * 
	 * @since 2.3
	 */
	private CipherSuiteSelector cipherSuiteSelector;

	/**
	 * Preselected cipher suites.
	 * 
	 * If no supported cipher suites are provided, consider only this subset of
	 * {@link CipherSuite} to be automatically selected as supported cipher
	 * suites depending on other setting (e.g. if settings allow only PSK, only
	 * PSK compatible cipher suite from this list will be selected).
	 * 
	 * Not used, if supported cipher suites are provided.
	 * 
	 * @see #supportedCipherSuites
	 * @since 2.5
	 */
	private List<CipherSuite> preselectedCipherSuites;

	/** the supported cipher suites in order of preference */
	private List<CipherSuite> supportedCipherSuites;

	/**
	 * the supported signature and hash algorithms in order of preference.
	 * 
	 * @since 2.3
	 */
	private List<SignatureAndHashAlgorithm> supportedSignatureAlgorithms;

	/**
	 * the supported groups (curves) in order of preference.
	 * 
	 * @since 2.3
	 */
	private List<SupportedGroup> supportedGroups;

	private Integer outboundMessageBufferSize;

	private Integer maxDeferredProcessedOutgoingApplicationDataMessages;

	private Integer maxDeferredProcessedIncomingRecordsSize;

	private Integer maxConnections;

	private Long staleConnectionThreshold;

	private Integer connectionThreadCount;

	private Integer receiverThreadCount;

	private Integer socketReceiveBufferSize;

	private Integer socketSendBufferSize;

	private Integer healthStatusInterval;

	/**
	 * Automatic session resumption timeout. Triggers session resumption
	 * automatically, if no messages are exchanged for this timeout. Intended to
	 * be used, if traffic is routed through a NAT. If {@code null}, no
	 * automatic session resumption is used. Value is in milliseconds.
	 */
	private Long autoResumptionTimeoutMillis;

	/**
	 * Indicates, that "server name indication" is used (client side) and
	 * supported (server side). The support on the server side currently
	 * includes a server name specific PSK secret lookup and to forward the
	 * server name to the CoAP stack in the {@link org.eclipse.californium.elements.EndpointContext}.
	 * 
	 * See <a href="https://tools.ietf.org/html/rfc6066#section-3" target="_blank">RFC 6066, Section 3</a>
	 */
	private Boolean sniEnabled;

	/**
	 * Defines the usage of the "extend master secret" extension.
	 * 
	 * See <a href="https://tools.ietf.org/html/rfc7627" target="_blank">RFC 7627</a>
	 * 
	 * @since 3.0
	 */
	private ExtendedMasterSecretMode extendedMasterSecretMode;

	/**
	 * Threshold of pending handshakes without verified peer for session
	 * resumption in percent of {@link #maxConnections}. If more such
	 * handshakes are pending, then use a verify request to ensure, that the
	 * used client hello is not spoofed.
	 * 
	 * <pre>
	 * 0 := always use a HELLO_VERIFY_REQUEST
	 * 1 ... 100 := dynamically determine to use a HELLO_VERIFY_REQUEST.
	 * </pre>
	 * 
	 * Default {@link #DEFAULT_VERIFY_PEERS_ON_RESUMPTION_THRESHOLD_IN_PERCENT}.
	 * 
	 * @see #getVerifyPeersOnResumptionThreshold()
	 */
	private Integer verifyPeersOnResumptionThreshold;

	/**
	 * Enable/Disable the server's HELLO_VERIFY_REQUEST, if peers shares at
	 * least one PSK based cipher suite.
	 * <p>
	 * <b>Note:</b> it is not recommended to disable the HELLO_VERIFY_REQUEST! See
	 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
	 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>.
	 * </p>
	 * To limit the amplification, the peers must share PSK cipher suites to by
	 * pass that check. If only certificate based cipher suites are shared, the
	 * HELLO_VERIFY_REQUEST will still be used.
	 * 
	 * @see #useHelloVerifyRequest
	 * @since 3.0
	 */
	private Boolean useHelloVerifyRequestForPsk;

	/**
	 * Generally enable/disable the server's HELLO_VERIFY_REQUEST.
	 * <p>
	 * <b>Note:</b> it is strongly not recommended to disable the HELLO_VERIFY_REQUEST
	 * if used with certificates! That creates a large amplification! See
	 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
	 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>.
	 * </p>
	 * 
	 * @see {@link #useHelloVerifyRequestForPsk}
	 * @since 3.0
	 */
	private Boolean useHelloVerifyRequest;

	/**
	 * Indicates, that a session id is used by this server. The sessions are
	 * cached by this server and can be resumed.
	 * 
	 * @since 3.0 (was useNoServerSessionId with inverse logic)
	 */
	private Boolean useServerSessionId;

	/**
	 * Use anti replay filter.
	 * 
	 * @see "http://tools.ietf.org/html/rfc6347#section-4.1"
	 */
	private Boolean useAntiReplayFilter;

	/**
	 * Use filter for record in window and before limit.
	 * 
	 * The value will be subtracted from to lower receive window boundary. A
	 * value of {@code -1} will set that calculated lower boundary to {@code 0}.
	 * Messages between lower receive window boundary and that calculated value
	 * will pass the filter, for other messages the filter is applied.
	 * 
	 * @see "http://tools.ietf.org/html/rfc6347#section-4.1"
	 * @since 2.4
	 */
	private Integer useExtendedWindowFilter;

	/**
	 * Use filter to update the ip-address from DTLS 1.2 CID
	 * records only for newer records based on epoch/sequence_number.
	 */
	private Boolean useCidUpdateAddressOnNewerRecordFilter;

	/**
	 * Logging tag.
	 * 
	 * Tag logging messages, if multiple connectors share the same logging
	 * instance.
	 */
	private String loggingTag;

	/**
	 * Connection id generator. {@code null}, if connection id is not supported.
	 * The generator may only support the use of a connection id without using
	 * it by itself. In that case
	 * {@link ConnectionIdGenerator#useConnectionId()} will return
	 * {@code false}.
	 */
	private ConnectionIdGenerator connectionIdGenerator;

	private ApplicationLevelInfoSupplier applicationLevelInfoSupplier;

	/**
	 * Use truncated certificate paths when sending the client's certificate message.
	 * @since 2.1
	 */
	private Boolean useTruncatedCertificatePathForClientsCertificateMessage;
	/**
	 * Use truncated certificate paths for verification.
	 * @since 2.1
	 */
	private Boolean useTruncatedCertificatePathForValidation;

	/**
	 * Connection Listener.
	 */
	private ConnectionListener connectionListener;

	/**
	 * Session store for {@link InMemoryConnectionStore}.
	 * 
	 * If a custom {@link ResumptionSupportingConnectionStore} is used, the
	 * session store must be provided directly to that implementation. In that
	 * case, the configured session store here will be ignored.
	 * 
	 * @see DTLSConnector#createConnectionStore
	 * @since 3.0
	 */
	private SessionStore sessionStore;

	/**
	 * Server side verifier for DTLS session resumption.
	 * 
	 * Supports none-blocking processing.
	 * 
	 * @since 3.0
	 */
	private ResumptionVerifier resumptionVerifier;

	private DtlsHealth healthHandler;

	private Boolean clientOnly;

	private Boolean recommendedCipherSuitesOnly;

	private Boolean recommendedSupportedGroupsOnly;

	private Boolean recommendedSignatureAndHashAlgorithmsOnly;

	private DtlsConnectorConfig() {
		// empty
	}

	/**
	 * Gets record size limit.
	 * 
	 * Included in the CLIENT_HELLO and SERVER_HELLO to negotiate the record
	 * size limit.
	 * 
	 * @return record size limit, or {@code null}, if not used.
	 * @since 2.4
	 */
	public Integer getRecordSizeLimit() {
		return recordSizeLimit;
	}

	/**
	 * Gets the maximum amount of message payload data that this connector can receive in a
	 * single DTLS record.
	 * <p>
	 * The code returned is either {@code null} or one of the following:
	 * <ul>
	 * <li>1 - 2^9 bytes</li>
	 * <li>2 - 2^10 bytes</li>
	 * <li>3 - 2^11 bytes</li>
	 * <li>4 - 2^12 bytes</li>
	 * </ul>
	 * 
	 * @return the code indicating the maximum payload length, or {@code null}.
	 */
	public Integer getMaxFragmentLengthCode() {
		return maxFragmentLengthCode;
	}

	/**
	 * Gets the maximum length of a reassembled fragmented handshake message.
	 * 
	 * @return maximum length, or {@code null}.
	 */
	public Integer getMaxFragmentedHandshakeMessageLength() {
		return maxFragmentedHandshakeMessageLength;
	}

	/**
	 * Gets enable to use UDP messages with multiple dtls records.
	 * 
	 * Default behavior enables the usage of multiple records, but disables it
	 * as back off after two retransmissions.
	 * 
	 * @return {@code true}, if enabled, {@code false}, otherwise. {@code null}
	 *         for default behavior.
	 * @since 2.4
	 */
	public Boolean useMultiRecordMessages() {
		return enableMultiRecordMessages;
	}

	/**
	 * Enable to use dtls records with multiple handshake messages.
	 * 
	 * Default behavior disables the usage on the server side, and enables the
	 * usage of multiple handshake messages on the client side, if the server
	 * send such dtls records.
	 * 
	 * @return {@code true}, if enabled, {@code false}, otherwise. {@code null}
	 *         for default behavior.
	 * @since 2.4
	 */
	public Boolean useMultiHandshakeMessageRecords() {
		return enableMultiHandshakeMessageRecords;
	}

	/**
	 * Get protocol version for hello verify requests to send.
	 * 
	 * Before version 2.5.0, Californium used fixed the protocol version DTLS
	 * 1.2 to send the HelloVerifyRequest. According
	 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target="_blank">RFC 6347,
	 * 4.2.1. Denial-of-Service Countermeasures</a>, that HelloVerifyRequest
	 * SHOULD be sent using protocol version DTLS 1.0. But that found to be
	 * ambiguous, because it's also requested that "The server MUST use the same
	 * version number in the HelloVerifyRequest that it would use when sending a
	 * ServerHello." With that, Californium from 2.6.0 on will, by default,
	 * reply the version the client sent in the HelloVerifyRequest, and will
	 * postpone the version negotiation until the client has verified it's
	 * endpoint ownership. If that client version is below DTLS 1.0, a DTLS 1.0
	 * will be used. If a different behavior is wanted, you may use the related
	 * setter to provide a fixed version for the HelloVerifyRequest. In order to
	 * provide backwards compatibility to version before 2.5.0 , configure to
	 * use protocol version DTLS 1.2.
	 * 
	 * @return fixed protocol version, or {@code null}, to reply the clients
	 *         version. Default is {@code null}.
	 * @see HelloVerifyRequest
	 * @since 2.5
	 */
	public ProtocolVersion getProtocolVersionForHelloVerifyRequests() {
		return protocolVersionForHelloVerifyRequests;
	}

	/**
	 * Gets the (initial) time to wait before a handshake flight of messages gets re-transmitted.
	 * 
	 * This timeout gets adjusted during the course of repeated re-transmission of a flight.
	 * The DTLS spec suggests an exponential back-off strategy, i.e. after each re-transmission the
	 * timeout value is doubled.
	 * 
	 * @return the (initial) time to wait in milliseconds
	 */
	public Integer getRetransmissionTimeout() {
		return retransmissionTimeout;
	}

	/**
	 * Gets the additional (initial) time to wait before a handshake flight of
	 * messages gets re-transmitted, when the other peer is expected to perform
	 * ECC calculations.
	 * 
	 * ECC calculations may be time intensive, especially for smaller
	 * micro-controllers without ecc-hardware support. The additional timeout
	 * prevents Californium from resending a flight too early. The extra time is
	 * used for the DTLS-client, if a ECDSA or ECDHE cipher suite is proposed,
	 * and for the DTLS-server, if a ECDSA or ECDHE cipher suite is selected.
	 * 
	 * This timeout is added to {@link #getRetransmissionTimeout()} and on each
	 * retransmission, the resulting time is doubled.
	 * 
	 * @return the additional (initial) time to wait in milliseconds. Default is
	 *         {@link #DEFAULT_ADDITIONAL_TIMEOUT_FOR_ECC_MS}.
	 * @since 3.0
	 */
	public Integer getAdditionalTimeoutForEcc() {
		return additionalTimeoutForEcc;
	}

	/**
	 * Gets the maximum number of deferred processed outgoing application data messages.
	 * 
	 * @return the maximum number of deferred processed outgoing application data messages
	 */
	public Integer getMaxDeferredProcessedOutgoingApplicationDataMessages() {
		return maxDeferredProcessedOutgoingApplicationDataMessages;
	}

	/**
	 * Gets the maximum size of all deferred processed incoming records.
	 * 
	 * @return the maximum size of all deferred processed incoming records
	 */
	public Integer getMaxDeferredProcessedIncomingRecordsSize() {
		return maxDeferredProcessedIncomingRecordsSize;
	}

	/**
	 * Number of retransmissions before the attempt to transmit a flight in
	 * back-off mode.
	 * 
	 * <a href="https://tools.ietf.org/html/rfc6347#page-12" target="_blank">
	 * RFC 6347, Section 4.1.1.1, Page 12</a>
	 * 
	 * In back-off mode, UDP datagrams of maximum 512 bytes, or the negotiated
	 * records size, if that is smaller, are used. Each handshake message is
	 * placed in one dtls record, or more dtls records, if the handshake message
	 * is too large and must be fragmented. Beside of the CCS and FINISH dtls
	 * records, which send together in one UDP datagram, all other records are
	 * send in separate datagrams.
	 * 
	 * The {@link #useMultiHandshakeMessageRecords()} and
	 * {@link #useMultiRecordMessages()} has precedence over the back-off
	 * definition.
	 * 
	 * Value {@code 0}, to disable it, default is value
	 * {@link #maxRetransmissions} / 2.
	 * 
	 * @return the number of re-transmissions to use the back-off mode
	 * @since 2.4
	 */
	public Integer getBackOffRetransmission() {
		return backOffRetransmission;
	}

	/**
	 * Gets the maximum number of times a flight of handshake messages gets re-transmitted
	 * to a peer.
	 * 
	 * @return the maximum number of re-transmissions
	 */
	public Integer getMaxRetransmissions() {
		return maxRetransmissions;
	}

	/**
	 * Gets the maximum transmission unit.
	 * 
	 * Maximum number of bytes sent in one transmission.
	 * 
	 * @return maximum transmission unit
	 */
	public Integer getMaxTransmissionUnit() {
		return maxTransmissionUnit;
	}

	/**
	 * Gets the maximum transmission unit limit for auto detection.
	 * 
	 * Limit Maximum number of bytes sent in one transmission.
	 * 
	 * @return maximum transmission unit limit. Default
	 *         {@value #DEFAULT_MAX_TRANSMISSION_UNIT_LIMIT}.
	 * @since 2.3
	 */
	public Integer getMaxTransmissionUnitLimit() {
		return maxTransmissionUnitLimit;
	}

	/**
	 * @return true if retransmissions should be stopped as soon as we receive
	 *         handshake message
	 */
	public Boolean isEarlyStopRetransmission() {
		return earlyStopRetransmission;
	}

	/**
	 * @return {@code true}, if address reuse should be enabled for the socket.
	 */
	public Boolean isAddressReuseEnabled() {
		return enableReuseAddress;
	}

	/**
	 * Checks whether the connector should support the use of the TLS
	 * <a href="https://tools.ietf.org/html/rfc6066#section-3" target="_blank">
	 * Server Name Indication extension</a> in the DTLS handshake.
	 * <p>
	 * If enabled, the client side should send a server name extension, if the
	 * server is specified with hostname rather then with a raw ip-address. The
	 * server side support currently includes a server name specific PSK secret
	 * lookup and a forwarding of the server name to the CoAP stack in the
	 * {@link DtlsEndpointContext}. The x509 or RPK credentials lookup is
	 * currently not server name specific, therefore the server's certificate
	 * will be the same, regardless of the indicated server name.
	 * <p>
	 * The default value of this property is {@code null}. If this property is
	 * not set explicitly using {@link Builder#setSniEnabled(boolean)}, then the
	 * {@link Builder#build()} method will set it to {@code false}.
	 * 
	 * @return {@code true}, if SNI should be used.
	 */
	public Boolean isSniEnabled() {
		return sniEnabled;
	}

	/**
	 * Gets the <em>Extended Master Secret</em> TLS extension mode.
	 * 
	 * <p>
	 * See <a href="https://tools.ietf.org/html/rfc7627" target="_blank">
	 * RFC7627, Extended Master Secret extension</a> and
	 * {@link ExtendedMasterSecretMode} for details.
	 * </p>
	 * <p>
	 * The default value of this property is {@code null}. If this property is
	 * not set explicitly using
	 * {@link Builder#setExtendedMasterSecretMode(ExtendedMasterSecretMode)},
	 * then the {@link Builder#build()} method will set it to
	 * {@link ExtendedMasterSecretMode#ENABLED}.
	 * </p>
	 * 
	 * @return the extended master secret mode.
	 * @since 3.0
	 */
	public ExtendedMasterSecretMode getExtendedMasterSecretMode() {
		return extendedMasterSecretMode;
	}

	/**
	 * Threshold to use a HELLO_VERIFY_REQUEST also for session resumption in
	 * percent of {@link #getMaxConnections()}. Though a CLIENT_HELLO with an
	 * session id is used in session resumption, that session ID could be used
	 * as weaker verification, that the peer controls the source address.
	 * 
	 * <pre>
	 * Value 
	 * 0 : always use a verify request.
	 * 1 ... 100 : dynamically use a verify request.
	 * </pre>
	 * 
	 * Peers are identified by their endpoint (ip-address and port). To protect
	 * the server from congestion by address spoofing, a HELLO_VERIFY_REQUEST is
	 * used. That adds one exchange and with that, additional latency. In cases
	 * of session resumption, the server may also use the dtls session ID as a
	 * weaker proof of a valid client. Unfortunately there are several
	 * elaborated attacks to that (e.g. on-path-attacker may alter the
	 * source-address). To mitigate this vulnerability, this threshold defines a
	 * maximum percentage of handshakes without HELLO_VERIFY_REQUEST. If more
	 * resumption handshakes without verified peers are pending than this
	 * threshold, then a HELLO_VERIFY_REQUEST is used again. Additionally, if a
	 * peer resumes a session (by id), but a different session is related to its
	 * endpoint, then a verify request is used to ensure, that the peer really
	 * owns that endpoint.
	 * <p>
	 * <b>Note:</b> a value larger than 0 will call the {@link ResumptionVerifier}. If
	 * that implementation is expensive, please ensure, that this value is
	 * configured with {@code 0}. Otherwise, CLIENT_HELLOs with invalid session
	 * IDs may be spoofed and gets too expensive.
	 * </p>
	 * @return threshold handshakes without verified peer in percent of
	 *         {@link #getMaxConnections()}.
	 * @see HelloVerifyRequest
	 */
	public Integer getVerifyPeersOnResumptionThreshold() {
		return verifyPeersOnResumptionThreshold;
	}

	/**
	 * Enable/disable the server's HELLO_VERIFY_REQUEST, if peers shares at
	 * least one PSK based cipher suite.
	 * <p>
	 * <b>Note:</b> it is not recommended to disable the HELLO_VERIFY_REQUEST!
	 * See <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
	 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>.
	 * </p>
	 * To limit the amplification, the peers must share PSK cipher suites to by
	 * pass that check. If only certificate based cipher suites are shared, the
	 * HELLO_VERIFY_REQUEST will still be used.
	 * 
	 * @return {@code true}, if a HELLO_VERIFY_REQUEST should be send to the
	 *         client, {@code false}, if no HELLO_VERIFY_REQUEST is used.
	 * @see HelloVerifyRequest
	 * @see #useHelloVerifyRequest()
	 * @since 3.0
	 */
	public Boolean useHelloVerifyRequestForPsk() {
		return useHelloVerifyRequestForPsk;
	}

	/**
	 * Generally enable/disable the server's HELLO_VERIFY_REQUEST.
	 * <p>
	 * <b>Note:</b> it is strongly not recommended to disable the HELLO_VERIFY_REQUEST
	 * for certificates! That creates a large amplification! See
	 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
	 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>.
	 * </p>
	 * @return {@code true}, if a HELLO_VERIFY_REQUEST should be send to the
	 *         client, {@code false}, if no HELLO_VERIFY_REQUEST is used.
	 * @see HelloVerifyRequest
	 * @see #useHelloVerifyRequestForPsk()
	 * @since 3.0
	 */
	public Boolean useHelloVerifyRequest() {
		return useHelloVerifyRequest;
	}

	/**
	 * Gets connection ID generator.
	 * 
	 * @return connection id generator. {@code null} for not supported. The
	 *         returned generator may only support the use of a connection id
	 *         without using it by itself. In that case
	 *         {@link ConnectionIdGenerator#useConnectionId()} will return
	 *         {@code false}.
	 */
	public ConnectionIdGenerator getConnectionIdGenerator() {
		return connectionIdGenerator;
	}

	/**
	 * Gets the number of outbound messages that can be buffered in memory before
	 * messages are dropped.
	 * 
	 * @return the number of messages
	 */
	public Integer getOutboundMessageBufferSize() {
		return outboundMessageBufferSize;
	}

	/**
	 * Gets the IP address and port the connector is bound to.
	 * 
	 * @return the address
	 */
	public InetSocketAddress getAddress() {
		return address;
	}

	/**
	 * Gets the certificate identity provider.
	 * 
	 * @return the certificate identity provider, or {@code null}, if the
	 *         connector is not supposed to support certificate based
	 *         authentication
	 * @since 3.0
	 */
	public CertificateProvider getCertificateIdentityProvider() {
		return certificateIdentityProvider;
	}

	/**
	 * Get cipher suite selector.
	 * 
	 * @return cipher suite selector. Default
	 *         {@link DefaultCipherSuiteSelector}.
	 * @since 2.3
	 */
	public CipherSuiteSelector getCipherSuiteSelector() {
		return cipherSuiteSelector;
	}

	/**
	 * Gets the preselected cipher suites.
	 * 
	 * If no supported cipher suites are provided, consider only this subset of
	 * {@link CipherSuite} to be automatically selected as supported cipher
	 * suites depending on other setting (e.g. if settings allow only PSK, only
	 * PSK compatible cipher suite from this list will be selected).
	 * 
	 * Not used, if supported cipher suites are provided.
	 * 
	 * @return the preselected cipher suites
	 * @see #getSupportedCipherSuites()
	 * @since 2.5
	 */
	public List<CipherSuite> getPreselectedCipherSuites() {
		return preselectedCipherSuites;
	}

	/**
	 * Gets the supported cipher suites.
	 * 
	 * On the client side the connector advertise these cipher suites in a DTLS
	 * handshake. On the server side the connector limits the acceptable cipher
	 * suites to this list.
	 * 
	 * @return the supported cipher suites (ordered by preference)
	 */
	public List<CipherSuite> getSupportedCipherSuites() {
		return supportedCipherSuites;
	}

	/**
	 * Gets the supported signature and hash algorithms the connector should
	 * advertise in a DTLS handshake.
	 * 
	 * @return the supported signature and hash algorithms (ordered by
	 *         preference). If empty, the client does not advertise it's
	 *         supported signature and hash algorithms, and the server assumes
	 *         the {@link SignatureAndHashAlgorithm#DEFAULT} as list of
	 *         supported signature and hash algorithms
	 * @since 2.3
	 */
	public List<SignatureAndHashAlgorithm> getSupportedSignatureAlgorithms() {
		return supportedSignatureAlgorithms;
	}

	/**
	 * Gets the supported groups (curves).
	 * 
	 * On the client side the connector advertise these supported groups
	 * (curves) in a DTLS handshake. On the server side the connector limits the
	 * acceptable supported groups (curves) to this list. According
	 * <a href="https://tools.ietf.org/html/rfc8422#page-11" target= "_blank">RFC 8422, 5.1.
	 * Client Hello Extensions, Actions of the receiver</a> This affects both,
	 * curves for ECDH and the certificates for ECDSA.
	 * 
	 * @return the supported groups (curves, ordered by preference)
	 * 
	 * @since 2.3
	 */
	public List<SupportedGroup> getSupportedGroups() {
		return supportedGroups;
	}

	/**
	 * Gets the advanced registry of <em>shared secrets</em> used for
	 * authenticating clients during a DTLS handshake.
	 * 
	 * @return the registry
	 * @since 2.3
	 */
	public AdvancedPskStore getAdvancedPskStore() {
		return advancedPskStore;
	}

	/**
	 * Gets the new advanced certificate verifier to be used during the DTLS
	 * handshake.
	 * 
	 * @return the new advanced certificate verifier
	 * @since 2.5
	 */
	public NewAdvancedCertificateVerifier getAdvancedCertificateVerifier() {
		return advancedCertificateVerifier;
	}

	/**
	 * Gets the supplier of application level information for an authenticated peer's identity.
	 * 
	 * @return the supplier or {@code null} if not set
	 */
	public ApplicationLevelInfoSupplier getApplicationLevelInfoSupplier() {
		return applicationLevelInfoSupplier;
	}

	/**
	 * Gets whether the connector wants (requests) DTLS x509/RPK clients to
	 * authenticate during the handshake. The handshake doesn't fail, if the
	 * client didn't authenticate itself during the handshake. That mostly
	 * requires the client to use a proprietary mechanism to authenticate itself
	 * on the application layer (e.g. username/password). It's mainly used, if
	 * the implementation of the other peer has no PSK cipher suite and client
	 * certificate should not be used for some reason.
	 * 
	 * Only used by the DTLS server side.
	 * 
	 * @return {@code true}, if clients wanted to authenticate
	 */
	public Boolean isClientAuthenticationWanted() {
		return clientAuthenticationWanted;
	}

	/**
	 * Gets whether the connector requires DTLS x509/RPK clients to authenticate
	 * during the handshake. Only used by the DTLS server side.
	 * 
	 * @return {@code true}, if clients need to authenticate
	 */
	public Boolean isClientAuthenticationRequired() {
		return clientAuthenticationRequired;
	}

	/**
	 * Gets whether the connector acts only as server and doesn't start new handshakes.
	 * 
	 * @return {@code true}, if the connector acts only as server
	 */
	public Boolean isServerOnly() {
		return serverOnly;
	}

	/**
	 * Get the default handshake mode.
	 * 
	 * Used, if no handshake mode is provided in the endpoint context, see
	 * {@link DtlsEndpointContext#KEY_HANDSHAKE_MODE}.
	 * 
	 * @return default handshake mode.
	 *         {@link DtlsEndpointContext#HANDSHAKE_MODE_NONE} or
	 *         {@link DtlsEndpointContext#HANDSHAKE_MODE_AUTO} (default)
	 * @since 2.1
	 */
	public String getDefaultHandshakeMode() {
		return defaultHandshakeMode;
	}

	/**
	 * Gets the certificate types for the identity of this peer.
	 * 
	 * In the order of preference.
	 * 
	 * @return certificate types ordered by preference, or {@code null}, if no
	 *         certificates are used to identify this peer.
	 */
	public List<CertificateType> getIdentityCertificateTypes() {
		if (certificateIdentityProvider == null) {
			return null;
		}
		return certificateIdentityProvider.getSupportedCertificateTypes();
	}

	/**
	 * Gets the certificate types for the trust of the other peer.
	 * 
	 * In the order of preference.
	 * 
	 * @return certificate types ordered by preference, or {@code null}, if no
	 *         certificates are used to trust the other peer.
	 */
	public List<CertificateType> getTrustCertificateTypes() {
		if (advancedCertificateVerifier == null) {
			return null;
		}
		return advancedCertificateVerifier.getSupportedCertificateTypes();
	}

	/**
	 * Gets the maximum number of (active) connections the connector will support.
	 * <p>
	 * Once this limit is reached, new connections will only be accepted if <em>stale</em>
	 * connections exist. A stale connection is one that hasn't been used for at least
	 * <em>staleConnectionThreshold</em> seconds.
	 * 
	 * @return The maximum number of active connections supported.
	 * @see #getStaleConnectionThreshold()
	 */
	public Integer getMaxConnections() {
		return maxConnections;
	}

	/**
	 * Gets the maximum number of seconds within which some records need to be exchanged
	 * over a connection before it is considered <em>stale</em>.
	 * <p>
	 * Once a connection becomes stale, it cannot be used to transfer DTLS records anymore.
	 * 
	 * @return The number of seconds.
	 * @see #getMaxConnections()
	 */
	public Long getStaleConnectionThreshold() {
		return staleConnectionThreshold;
	}

	/**
	 * Gets the number of threads which should be use to handle DTLS connection.
	 * <p>
	 * The default value is 6 * <em>#(CPU cores)</em>.
	 * 
	 * @return the number of threads.
	 */
	public Integer getConnectionThreadCount() {
		return connectionThreadCount;
	}

	/**
	 * Gets the number of threads which should be use to receive datagrams
	 * from the socket.
	 * <p>
	 * The default value is half of <em>#(CPU cores)</em>.
	 * 
	 * @return the number of threads.
	 */
	public Integer getReceiverThreadCount() {
		return receiverThreadCount;
	}

	/**
	 * Gets size of the socket receive buffer.
	 * 
	 * @return the socket receive buffer in bytes, or {@code null}, to use the OS default.
	 */
	public Integer getSocketReceiveBufferSize() {
		return socketReceiveBufferSize;
	}

	/**
	 * Gets size of the socket send buffer.
	 * 
	 * @return the socket send buffer in bytes, or {@code null}, to use the OS default.
	 */
	public Integer getSocketSendBufferSize() {
		return socketSendBufferSize;
	}

	/**
	 * Get the timeout for automatic session resumption.
	 * 
	 * If no messages are exchanged for this timeout, the next message will
	 * trigger a session resumption automatically. Intended to be used, if
	 * traffic is routed over a NAT. The value may be overridden by the endpoint
	 * context attribute {@link DtlsEndpointContext#KEY_RESUMPTION_TIMEOUT}.
	 * 
	 * @return timeout in milliseconds, or {@code null}, if no automatic
	 *         resumption is intended.
	 */
	public Long getAutoResumptionTimeoutMillis() {
		return autoResumptionTimeoutMillis;
	}

	/**
	 * Indicates, that session id is used by this server and so session are
	 * cached by this server and can be resumed.
	 * 
	 * @return {@code true}, if session id is used by this server,
	 *         {@code false}, if no session id us used by this server and
	 *         therefore the session can not be resumed. Default {@code true}.
	 * @since 3.0 (was useNoServerSessionId with inverse logic)
	 */
	public Boolean useServerSessionId() {
		return useServerSessionId;
	}

	/**
	 * Use anti replay filter.
	 * 
	 * @return {@code true}, apply anti replay filter
	 * @see "http://tools.ietf.org/html/rfc6347#section-4.1"
	 */
	public Boolean useAntiReplayFilter() {
		return useAntiReplayFilter;
	}

	/**
	 * Use filter for records in window and before limit.
	 * 
	 * The value will be subtracted from to lower receive window boundary. A
	 * value of {@code -1} will set that calculated lower boundary to {@code 0}.
	 * Messages between lower receive window boundary and that calculated value
	 * will pass the filter, for other messages the filter is applied.
	 * 
	 * @return value to extend lower receive window boundary, {@code -1}, to
	 *         extend lower boundary to {@code 0}, {@code 0} to disable extended
	 *         window filter.
	 * @see "http://tools.ietf.org/html/rfc6347#section-4.1"
	 * @since 2.4
	 */
	public Integer useExtendedWindowFilter() {
		return useExtendedWindowFilter;
	}

	/**
	 * Use filter to update the ip-address from DTLS 1.2 CID
	 * records only for newer records based on epoch/sequence_number.
	 * 
	 * @return {@code true}, apply the newer filter
	 */
	public Boolean useCidUpdateAddressOnNewerRecordFilter() {
		return useCidUpdateAddressOnNewerRecordFilter;
	}

	/**
	 * Use truncated certificate paths for client's certificate message.
	 * 
	 * Truncate certificate path according the received certificate
	 * authorities in the {@link CertificateRequest} for the client's
	 * {@link CertificateMessage}.
	 * 
	 * @return {@code true}, if path should be truncated for client's
	 *         certificate message.
	 * @since 2.1
	 */
	public Boolean useTruncatedCertificatePathForClientsCertificateMessage() {
		return useTruncatedCertificatePathForClientsCertificateMessage;
	}

	/**
	 * Use truncated certificate paths for validation.
	 * 
	 * Truncate certificate path according the available trusted
	 * certificates before validation.
	 * 
	 * @return {@code true}, if path should be truncated at available trust
	 *         anchors for validation
	 * @since 2.1
	 */
	public Boolean useTruncatedCertificatePathForValidation() {
		return useTruncatedCertificatePathForValidation;
	}

	public ConnectionListener getConnectionListener() {
		return connectionListener;
	}

	/**
	 * Gets session store for {@link InMemoryConnectionStore}.
	 * 
	 * If a custom {@link ResumptionSupportingConnectionStore} is used, the
	 * session store must be provided directly to that implementation. In that
	 * case, the configured session store here will be ignored.
	 * 
	 * @return session store, or {@code null}, if not provided.
	 * 
	 * @see DTLSConnector#createConnectionStore
	 * @since 3.0
	 */
	public SessionStore getSessionStore() {
		return sessionStore;
	}

	/**
	 * Gets the resumption verifier.
	 * 
	 * If the client provides a session id in the client hello, this verifier is
	 * used to ensure, that a valid session to resume is available. An
	 * implementation may check a maximum time, or, if the credentials are
	 * expired (e.g. x509 valid range). The default verifier will just checks,
	 * if a DTLS session with that session id is available in the
	 * {@link ResumptionSupportingConnectionStore}.
	 * 
	 * @return resumption verifier. May be {@code null}, if
	 *         {@link #useServerSessionId()} is {@code false} and session
	 *         resumption is not supported.
	 * @since 3.0
	 */
	public ResumptionVerifier getResumptionVerifier() {
		return resumptionVerifier;
	}

	/**
	 * Get instance logging tag.
	 * 
	 * @return logging tag.
	 */
	public String getLoggingTag() {
		return loggingTag;
	}

	/**
	 * Gets health status interval.
	 * 
	 * @return health status interval in seconds.
	 */
	public Integer getHealthStatusInterval() {
		return healthStatusInterval;
	}

	/**
	 * Gets health handler.
	 * 
	 * @return health handler.
	 */
	public DtlsHealth getHealthHandler() {
		return healthHandler;
	}

	/**
	 * Gets whether the connector acts only as client.
	 * 
	 * @return <code>true</code> if the connector acts only as client
	 * @see Builder#setClientOnly()
	 */
	public Boolean isClientOnly() {
		return clientOnly;
	}

	/**
	 * @return <code>true</code> if only recommended cipher suites are used.
	 * @see Builder#setRecommendedCipherSuitesOnly(boolean)
	 */
	public Boolean isRecommendedCipherSuitesOnly() {
		return recommendedCipherSuitesOnly;
	}

	/**
	 * @return <code>true</code> if only recommended supported groups (curves) are used.
	 * @see Builder#setRecommendedSupportedGroupsOnly(boolean)
	 * 
	 * @since 2.3
	 */
	public Boolean isRecommendedSupportedGroupsOnly() {
		return recommendedSupportedGroupsOnly;
	}

	/**
	 * @return <code>true</code> if only recommended signature and hash
	 *         algorithms are used.
	 * @see Builder#setRecommendedSupportedGroupsOnly(boolean)
	 * 
	 * @since 3.0
	 */
	public Boolean isRecommendedSignatureAndHashAlgorithmsOnly() {
		return recommendedSignatureAndHashAlgorithmsOnly;
	}

	/**
	 * @return a copy of this configuration
	 */
	@Override
	protected Object clone() {
		DtlsConnectorConfig cloned = new DtlsConnectorConfig();
		cloned.address = address;
		cloned.advancedCertificateVerifier = advancedCertificateVerifier;
		cloned.earlyStopRetransmission = earlyStopRetransmission;
		cloned.enableReuseAddress = enableReuseAddress;
		cloned.recordSizeLimit = recordSizeLimit;
		cloned.maxFragmentLengthCode = maxFragmentLengthCode;
		cloned.maxFragmentedHandshakeMessageLength = maxFragmentedHandshakeMessageLength;
		cloned.enableMultiRecordMessages = enableMultiRecordMessages;
		cloned.enableMultiHandshakeMessageRecords = enableMultiHandshakeMessageRecords;
		cloned.protocolVersionForHelloVerifyRequests = protocolVersionForHelloVerifyRequests;
		cloned.retransmissionTimeout = retransmissionTimeout;
		cloned.additionalTimeoutForEcc = additionalTimeoutForEcc;
		cloned.maxRetransmissions = maxRetransmissions;
		cloned.maxTransmissionUnit = maxTransmissionUnit;
		cloned.maxTransmissionUnitLimit = maxTransmissionUnitLimit;
		cloned.clientAuthenticationRequired = clientAuthenticationRequired;
		cloned.clientAuthenticationWanted = clientAuthenticationWanted;
		cloned.serverOnly = serverOnly;
		cloned.defaultHandshakeMode = defaultHandshakeMode;
		cloned.advancedPskStore = advancedPskStore;
		cloned.certificateIdentityProvider = certificateIdentityProvider;
		cloned.certificateConfigurationHelper = certificateConfigurationHelper;
		cloned.cipherSuiteSelector = cipherSuiteSelector;
		cloned.preselectedCipherSuites = preselectedCipherSuites;
		cloned.supportedCipherSuites = supportedCipherSuites;
		cloned.supportedSignatureAlgorithms = supportedSignatureAlgorithms;
		cloned.supportedGroups = supportedGroups;
		cloned.outboundMessageBufferSize = outboundMessageBufferSize;
		cloned.maxDeferredProcessedOutgoingApplicationDataMessages = maxDeferredProcessedOutgoingApplicationDataMessages;
		cloned.maxDeferredProcessedIncomingRecordsSize = maxDeferredProcessedIncomingRecordsSize;
		cloned.maxConnections = maxConnections;
		cloned.staleConnectionThreshold = staleConnectionThreshold;
		cloned.connectionThreadCount = connectionThreadCount;
		cloned.receiverThreadCount = receiverThreadCount;
		cloned.socketReceiveBufferSize = socketReceiveBufferSize;
		cloned.socketSendBufferSize = socketSendBufferSize;
		cloned.healthStatusInterval = healthStatusInterval;
		cloned.autoResumptionTimeoutMillis = autoResumptionTimeoutMillis;
		cloned.sniEnabled = sniEnabled;
		cloned.extendedMasterSecretMode = extendedMasterSecretMode;
		cloned.verifyPeersOnResumptionThreshold = verifyPeersOnResumptionThreshold;
		cloned.useHelloVerifyRequestForPsk = useHelloVerifyRequestForPsk;
		cloned.useHelloVerifyRequest = useHelloVerifyRequest;
		cloned.useServerSessionId = useServerSessionId;
		cloned.loggingTag = loggingTag;
		cloned.useAntiReplayFilter = useAntiReplayFilter;
		cloned.useExtendedWindowFilter = useExtendedWindowFilter;
		cloned.useCidUpdateAddressOnNewerRecordFilter = useCidUpdateAddressOnNewerRecordFilter;
		cloned.connectionIdGenerator = connectionIdGenerator;
		cloned.applicationLevelInfoSupplier = applicationLevelInfoSupplier;
		cloned.useTruncatedCertificatePathForClientsCertificateMessage = useTruncatedCertificatePathForClientsCertificateMessage;
		cloned.useTruncatedCertificatePathForValidation = useTruncatedCertificatePathForValidation;
		cloned.connectionListener = connectionListener;
		cloned.sessionStore = sessionStore;
		cloned.resumptionVerifier = resumptionVerifier;
		cloned.healthHandler = healthHandler;
		cloned.clientOnly = clientOnly;
		cloned.recommendedCipherSuitesOnly = recommendedCipherSuitesOnly;
		cloned.recommendedSupportedGroupsOnly = recommendedSupportedGroupsOnly;
		return cloned;
	}

	/**
	 * Create new builder for DtlsConnectorConfig.
	 * 
	 * @return created builder
	 * @since 2.5
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Create builder for DtlsConnectorConfig from provided DtlsConnectorConfig.
	 * 
	 * @param config DtlsConnectorConfig to clone
	 * @return created builder
	 * @since 2.5
	 */
	public static Builder builder(DtlsConnectorConfig config) {
		return new Builder(config);
	}

	/**
	 * A helper for creating instances of <code>DtlsConnectorConfig</code>
	 * based on the builder pattern.
	 *
	 */
	public static final class Builder {

		private DtlsConnectorConfig config;

		/**
		 * Creates a new instance for setting configuration options
		 * for a <code>DTLSConnector</code> instance.
		 * 
		 * Once all options are set, clients should use the {@link #build()}
		 * method to create an immutable <code>DtlsConfigurationConfig</code>
		 * instance which can be passed into the <code>DTLSConnector</code>
		 * constructor.
		 * 
		 * The builder is initialized to the following default values
		 * <ul>
		 * <li><em>address</em>: a wildcard address with a system chosen ephemeral port
		 *  see {@link InetSocketAddress#InetSocketAddress(int)}</li>
		 * <li><em>maxFragmentLength</em>: 4096 bytes</li>
		 * <li><em>maxPayloadSize</em>: 4096 + 25 bytes (max fragment size + 25 bytes for headers)</li>
		 * <li><em>maxRetransmissions</em>: 4</li>
		 * <li><em>retransmissionTimeout</em>: 1000ms</li>
		 * <li><em>clientAuthenticationRequired</em>: <code>true</code></li>
		 * <li><em>outboundMessageBufferSize</em>: 100.000</li>
		 * <li><em>trustStore</em>: empty array</li>
		 * </ul>
		 * 
		 * Note that when keeping the default values, at least one of the
		 * {@link #setAdvancedPskStore(AdvancedPskStore)} or
		 * {@link #setCertificateIdentityProvider(CertificateProvider)} methods need to
		 * be used to get a working configuration for a
		 * <code>DTLSConnector</code> that can be used as a client and server.
		 * 
		 * It is possible to create a configuration for a
		 * <code>DTLSConnector</code> that can operate as a client only without
		 * the need for setting an identity. However, this is possible only if
		 * the server does not require clients to authenticate, i.e. this only
		 * works with the ECDH based cipher suites. If you want to create such a
		 * <em>client-only</em> configuration, you need to use the
		 * {@link #setClientOnly()} method on the builder.
		 */
		public Builder() {
			config = new DtlsConnectorConfig();
		}

		/**
		 * Create a builder from an existing DtlsConnectorConfig. This allow to
		 * create a new configuration starting from values of another one.
		 * 
		 * @param initialConfiguration initial configuration
		 */
		public Builder(DtlsConnectorConfig initialConfiguration) {
			config = (DtlsConnectorConfig) initialConfiguration.clone();
		}

		/**
		 * Sets the IP address and port the connector should bind to
		 * 
		 * Note: using IPv6 interfaces with multiple addresses including
		 * permanent and temporary (with potentially several different prefixes)
		 * currently causes issues on the server side. The outgoing traffic in
		 * response to incoming may select a different source address than the
		 * incoming destination address. To overcome this, please ensure that
		 * the 'any address' is not used on the server side and a separate
		 * Connector is created for each address to receive incoming traffic.
		 * 
		 * @param address the IP address and port the connector should bind to
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the given address is unresolved
		 */
		public Builder setAddress(InetSocketAddress address) {
			if (address.isUnresolved()) {
				throw new IllegalArgumentException("Bind address must not be unresolved");
			}
			config.address = address;
			return this;
		}

		/**
		 * Enables address reuse for the socket.
		 * 
		 * @param enable {@code true} if addresses should be reused.
		 * @return this builder for command chaining
		 */
		public Builder setEnableAddressReuse(boolean enable) {
			config.enableReuseAddress = enable;
			return this;
		}

		/**
		 * Set usage of recommended cipher suites.
		 * 
		 * @param recommendedCipherSuitesOnly {@code true} allow only
		 *            recommended cipher suites, {@code false}, also allow not
		 *            recommended cipher suites. Default value is {@code true}
		 * @return this builder for command chaining
		 */
		public Builder setRecommendedCipherSuitesOnly(boolean recommendedCipherSuitesOnly) {
			config.recommendedCipherSuitesOnly = recommendedCipherSuitesOnly;
			if (recommendedCipherSuitesOnly && config.supportedCipherSuites != null) {
				verifyRecommendedCipherSuitesOnly(config.supportedCipherSuites);
			}
			return this;
		}

		/**
		 * Set usage of recommended supported groups (curves).
		 * 
		 * @param recommendedSupportedGroupsOnly {@code true} allow only
		 *            recommended supported groups, {@code false}, also allow not
		 *            recommended supported groups. Default value is {@code true}
		 * @return this builder for command chaining
		 * 
		 * @since 2.3
		 */
		public Builder setRecommendedSupportedGroupsOnly(boolean recommendedSupportedGroupsOnly) {
			config.recommendedSupportedGroupsOnly = recommendedSupportedGroupsOnly;
			if (recommendedSupportedGroupsOnly && config.supportedGroups != null) {
				verifyRecommendedSupportedGroupsOnly(config.supportedGroups);
			}
			return this;
		}

		/**
		 * Set usage of recommended signature and hash algorithms.
		 * 
		 * @param recommendedSignatureAndHashAlgorithmsOnly {@code true} allow
		 *            only recommended signature and hash algorithms,
		 *            {@code false}, also allow not recommended signature and
		 *            hash algorithms. Default value is {@code true}
		 * @return this builder for command chaining
		 * 
		 * @since 3.0
		 */
		public Builder setRecommendedSignatureAndHashAlgorithmsOnly(boolean recommendedSignatureAndHashAlgorithmsOnly) {
			config.recommendedSignatureAndHashAlgorithmsOnly = recommendedSignatureAndHashAlgorithmsOnly;
			if (recommendedSignatureAndHashAlgorithmsOnly && config.supportedSignatureAlgorithms != null) {
				verifyRecommendedSignatureAndHashAlgorithmsOnly(config.supportedSignatureAlgorithms);
			}
			return this;
		}

		/**
		 * Indicates that the <em>DTLSConnector</em> will only be used as a
		 * DTLS client.
		 * 
		 * The {@link #build()} method will allow creation of a configuration
		 * without any identity being set under the following conditions:
		 * <ul>
		 * <li>only support for ECDH based cipher suites is configured</li>
		 * <li>this method has been invoked</li>
		 * </ul>
		 * 
		 * @return this builder for command chaining
		 * @throws IllegalStateException if client only is in contradiction to
		 *             server side configuration
		 */
		public Builder setClientOnly() {
			if (Boolean.TRUE.equals(config.serverOnly)) {
				throw new IllegalStateException("client only is in contradiction to server only!");
			} else if (config.clientAuthenticationRequired != null || config.clientAuthenticationWanted != null) {
				throw new IllegalStateException(
						"client only is in contradiction to server side client authentication!");
			} else if (Boolean.FALSE.equals(config.useServerSessionId)) {
				throw new IllegalStateException(
						"client only is in contradiction to server side 'server session id'!");
			} else if (Boolean.FALSE.equals(config.useHelloVerifyRequestForPsk)) {
				throw new IllegalStateException(
						"client only is in contradiction to server side HELLO_VERIFY_REQUEST for PSK configuration!");
			} else if (Boolean.FALSE.equals(config.useHelloVerifyRequest)) {
				throw new IllegalStateException(
						"client only is in contradiction to server side HELLO_VERIFY_REQUEST configuration!");
			}

			config.clientOnly = true;
			return this;
		}

		/**
		 * Indicates that the <em>DTLSConnector</em> will only act as server.
		 * 
		 * A server only accepts handshakes, it never starts them.
		 * 
		 * @param enable {@code true} if the connector acts only as server.
		 * @return this builder for command chaining
		 * @throws IllegalStateException if server only is enabled in
		 *             contradiction to client side configuration
		 */
		public Builder setServerOnly(boolean enable) {
			if (enable) {
				if (Boolean.TRUE.equals(config.clientOnly)) {
					throw new IllegalStateException("server only is in contradiction to client only!");
				}
				if (config.defaultHandshakeMode != null && !config.defaultHandshakeMode.equals(DtlsEndpointContext.HANDSHAKE_MODE_NONE)) {
					throw new IllegalStateException("server only is in contradiction to default handshake mode '"
							+ config.defaultHandshakeMode + "!");
				}
			}
			config.serverOnly = enable;
			return this;
		}

		/**
		 * Set the <em>DTLSConnector</em> default handshake mode.
		 * 
		 * @param defaultHandshakeMode
		 *            {@link DtlsEndpointContext#HANDSHAKE_MODE_AUTO} or
		 *            {@link DtlsEndpointContext#HANDSHAKE_MODE_NONE}
		 * @return this builder for command chaining
		 * @throws IllegalStateException if configuration is server only and
		 *             {@link DtlsEndpointContext#HANDSHAKE_MODE_AUTO} is
		 *             provided
		 * @throws IllegalArgumentException if mode is neither
		 *             {@link DtlsEndpointContext#HANDSHAKE_MODE_AUTO} nor
		 *             {@link DtlsEndpointContext#HANDSHAKE_MODE_NONE}
		 * @since 2.1
		 */
		public Builder setDefaultHandshakeMode(String defaultHandshakeMode) {
			if (defaultHandshakeMode != null) {
				if (!defaultHandshakeMode.equals(DtlsEndpointContext.HANDSHAKE_MODE_AUTO)
						&& !defaultHandshakeMode.equals(DtlsEndpointContext.HANDSHAKE_MODE_NONE)) {
					throw new IllegalArgumentException(
							"default handshake mode must be either \"" + DtlsEndpointContext.HANDSHAKE_MODE_AUTO
									+ "\" or \"" + DtlsEndpointContext.HANDSHAKE_MODE_NONE + "\"!");
				}
			}
			if (config.serverOnly != null && config.serverOnly
					&& defaultHandshakeMode != null && !defaultHandshakeMode.equals(DtlsEndpointContext.HANDSHAKE_MODE_NONE)) {
				throw new IllegalStateException("default handshake modes are not supported for server only!");
			}
			config.defaultHandshakeMode = defaultHandshakeMode;
			return this;
		}

		/**
		 * Sets record size limit.
		 * 
		 * Included in the CLIENT_HELLO and SERVER_HELLO to negotiate the record
		 * size limit.
		 * 
		 * @param recordSizeLimit the record size limit, betwee 64 and 65535. Or
		 *            {@code null}, if not used.
		 * @return this builder for command chaining
		 * @since 2.4
		 */
		public Builder setRecordSizeLimit(Integer recordSizeLimit) {
			if (recordSizeLimit != null) {
				if (recordSizeLimit < 64 || recordSizeLimit > 65535) {
					throw new IllegalArgumentException(
							"Record size limit must be within [64...65535], not " + recordSizeLimit + "!");
				}
			}
			config.recordSizeLimit = recordSizeLimit;
			return this;
		}

		/**
		 * Sets the maximum amount of payload data that can be received and processed by this connector
		 * in a single DTLS record.
		 * <p>
		 * The value of this property is used to indicate to peers the
		 * <em>Maximum Fragment Length</em> as defined in
		 * <a href="https://tools.ietf.org/html/rfc6066#section-4" target=
		 * "_blank">RFC 6066, Section 4</a>. It is also used to determine the
		 * amount of memory that will be allocated for receiving UDP datagrams
		 * sent by peers from the network interface.
		 * </p>
		 * The code must be either {@code null} or one of the following:
		 * <ul>
		 * <li>1 - 2^9 bytes</li>
		 * <li>2 - 2^10 bytes</li>
		 * <li>3 - 2^11 bytes</li>
		 * <li>4 - 2^12 bytes</li>
		 * </ul>
		 * <p>
		 * If this property is set to {@code null}, the {@link DTLSConnector} will
		 * derive its value from the network interface's <em>Maximum Transmission Unit</em>.
		 * This means that it will set it to a value small enough to make sure that inbound
		 * messages fit into a UDP datagram having a size less or equal to the MTU.
		 * </p>
		 * 
		 * @param lengthCode the code indicating the maximum length or {@code null} to determine
		 *                   the maximum fragment length based on the network interface's MTU
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the code is not one of {1, 2, 3, 4} 
		 */
		public Builder setMaxFragmentLengthCode(Integer lengthCode) {
			if (lengthCode != null && (lengthCode < 1 || lengthCode > 4)) {
				throw new IllegalArgumentException("Maximum fragment length code must be one of {1, 2, 3, 4}");
			} else {
				config.maxFragmentLengthCode = lengthCode;
				return this;
			}
		}

		/**
		 * Set maximum length of handshake message.
		 * 
		 * @param length maximum length of handshake message
		 * @return this builder for command chaining
		 */
		public Builder setMaxFragmentedHandshakeMessageLength(Integer length) {
			config.maxFragmentedHandshakeMessageLength = length;
			return this;
		}

		/**
		 * Enable to use UDP messages with multiple dtls records.
		 * 
		 * @param enable {@code true}, to enabled, {@code false}, otherwise.
		 * @return this builder for command chaining
		 * @since 2.4
		 */
		public Builder setEnableMultiRecordMessages(boolean enable) {
			config.enableMultiRecordMessages = enable;
			return this;
		}

		/**
		 * Enable to use dtls records with multiple handshake messages.
		 * 
		 * @param enable {@code true}, to enabled, {@code false}, otherwise.
		 * @return this builder for command chaining
		 * @since 2.4
		 */
		public Builder setEnableMultiHandshakeMessageRecords(boolean enable) {
			config.enableMultiHandshakeMessageRecords = enable;
			return this;
		}

		/**
		 * Set the protocol version to be used to send hello verify requests.
		 * 
		 * Before version 2.5.0, Californium used fixed the protocol version
		 * DTLS 1.2 to send the HelloVerifyRequest. According
		 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
		 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>, that
		 * HelloVerifyRequest SHOULD be sent using protocol version DTLS 1.0.
		 * But that found to be ambiguous, because it's also requested that "The
		 * server MUST use the same version number in the HelloVerifyRequest
		 * that it would use when sending a ServerHello." With that, Californium
		 * from 2.6.0 on will, by default, reply the version the client sent in
		 * the HelloVerifyRequest, and will postpone the version negotiation
		 * until the client has verified it's endpoint ownership. If that client
		 * version is below DTLS 1.0, a DTLS 1.0 will be used. If a different
		 * behavior is wanted, you may use this setter to provide a fixed
		 * version for the HelloVerifyRequest. In order to provide backwards
		 * compatibility to version before 2.5.0, configure to use protocol
		 * version DTLS 1.2.
		 * 
		 * @param protocolVersion fixed protocol version to send hello verify
		 *            requests. {@code null} to reply the client's version.
		 * @return this builder for command chaining
		 * @see HelloVerifyRequest
		 * @since 2.5
		 */
		public Builder setProtocolVersionForHelloVerifyRequests(ProtocolVersion protocolVersion) {
			config.protocolVersionForHelloVerifyRequests = protocolVersion;
			return this;
		}

		/**
		 * Set the size of the socket receive buffer.
		 * 
		 * @param size the socket receive buffer size in bytes, or {@code null},
		 *            to use the OS default.
		 * @return this builder for command chaining
		 */
		public Builder setSocketReceiveBufferSize(Integer size) {
			config.socketReceiveBufferSize = size;
			return this;
		}

		/**
		 * Set the size of the socket send buffer.
		 * 
		 * @param size the socket send buffer size in bytes, or {@code null}, to
		 *            use the OS default.
		 * @return this builder for command chaining
		 */
		public Builder setSocketSendBufferSize(Integer size) {
			config.socketSendBufferSize = size;
			return this;
		}

		/**
		 * Set the health status interval.
		 * 
		 * @param healthStatusIntervalSeconds health status interval in seconds.
		 *            {@code null} disable health status.
		 * @return this builder for command chaining
		 */
		public Builder setHealthStatusInterval(Integer healthStatusIntervalSeconds) {
			config.healthStatusInterval = healthStatusIntervalSeconds;
			return this;
		}

		/**
		 * Set the health handler.
		 * 
		 * @param healthHandler health handler.
		 * @return this builder for command chaining
		 */
		public Builder setHealthHandler(DtlsHealth healthHandler) {
			config.healthHandler = healthHandler;
			return this;
		}

		/**
		 * Sets the number of outbound messages that can be buffered in memory before
		 * dropping messages.
		 * 
		 * @param capacity the number of messages to buffer
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if capacity &lt; 1
		 */
		public Builder setOutboundMessageBufferSize(int capacity) {
			if (capacity < 1) {
				throw new IllegalArgumentException("Outbound message buffer size must be at least 1");
			} else {
				config.outboundMessageBufferSize = capacity;
				return this;
			}
		}

		/**
		 * Number of retransmissions before the attempt to transmit a flight in
		 * back-off mode.
		 * 
		 * <a href="https://tools.ietf.org/html/rfc6347#page-12" target="_blank">
		 * RFC 6347, Section 4.1.1.1, Page 12</a>
		 * 
		 * In back-off mode, UDP datagrams of maximum 512 bytes or the
		 * negotiated records size, if that is smaller, are used. Each handshake
		 * message is placed in one dtls record, or more dtls records, if the
		 * handshake message is too large and must be fragmented. Beside of the
		 * CCS and FINISH dtls records, which send together in one UDP datagram,
		 * all other records are send in separate datagrams.
		 * 
		 * The {@link #useMultiHandshakeMessageRecords()} and
		 * {@link #useMultiRecordMessages()} has precedence over the back-off
		 * definition.
		 * 
		 * Value {@code 0}, to disable it, {@code null}, for default of
		 * {@link #maxRetransmissions} / 2.
		 * 
		 * @param count the number of re-transmissions to use the back-off mode
		 * @return this builder for command chaining
		 * @since 2.4
		 */
		public Builder setBackOffRetransmission(Integer count) {
			if (count != null && count < 0) {
				throw new IllegalArgumentException("number of retransmissions to back-off must not be negative");
			}
			config.backOffRetransmission = count;
			return this;
		}

		/**
		 * Sets the maximum number of times a flight of handshake messages gets re-transmitted
		 * to a peer.
		 * 
		 * @param count the maximum number of re-transmissions
		 * @return this builder for command chaining
		 */
		public Builder setMaxRetransmissions(int count) {
			if (count < 1) {
				throw new IllegalArgumentException("Maximum number of retransmissions must be greater than zero");
			} else {
				config.maxRetransmissions = count;
				return this;
			}
		}

		/**
		 * Set maximum transmission unit. Maximum number of bytes sent in one
		 * transmission.
		 * 
		 * @param mtu maximum transmission unit
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if
		 *             {@link #setMaxTransmissionUnitLimit(int)} was already set
		 */
		public Builder setMaxTransmissionUnit(int mtu) {
			if (config.maxTransmissionUnitLimit != null) {
				throw new IllegalArgumentException("MTU limit already set!");
			}
			config.maxTransmissionUnit = mtu;
			return this;
		}

		/**
		 * Set maximum transmission unit limit for auto detection.
		 * 
		 * Limits maximum number of bytes sent in one transmission.
		 *
		 * Note: previous versions took the local link MTU without limits. That
		 * results in possibly larger MTU, e.g. for localhost or some cloud
		 * nodes using "jumbo frames". If a larger MTU is required, please
		 * adjust this limit to the requires value or use
		 * {@link #setMaxTransmissionUnit(int)}.
		 * 
		 * @param limit maximum transmission unit limit. Default
		 *            {@link DtlsConnectorConfig#DEFAULT_MAX_TRANSMISSION_UNIT_LIMIT}
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if
		 *             {@link #setMaxTransmissionUnit(int)} was already set
		 * @since 2.3
		 */
		public Builder setMaxTransmissionUnitLimit(int limit) {
			if (config.maxTransmissionUnit != null) {
				throw new IllegalArgumentException("MTU already set!");
			}
			config.maxTransmissionUnitLimit = limit;
			return this;
		}

		/**
		 * Sets whether the connector wants (requests) DTLS clients to
		 * authenticate during the handshake. The handshake doesn't fail, if the
		 * client didn't authenticate itself during the handshake. That mostly
		 * requires the client to use a proprietary mechanism to authenticate
		 * itself on the application layer (e.g. username/password). It's mainly
		 * used, if the implementation of the other peer has no PSK cipher suite
		 * and client certificate should not be used for some reason.
		 * 
		 * The default is {@code false}. Only used by the DTLS server side.
		 * 
		 * @param authWanted {@code true} if clients wanted to authenticate
		 * @return this builder for command chaining
		 * @throws IllegalStateException if configuration is for client only
		 * @throws IllegalArgumentException if authWanted is {@code true}, but
		 *             {@link #setClientAuthenticationRequired(boolean)} was set
		 *             to {@code true} before.
		 */
		public Builder setClientAuthenticationWanted(boolean authWanted) {
			if (Boolean.TRUE.equals(config.clientOnly)) {
				throw new IllegalStateException("client authentication is not supported for client only!");
			}
			if (authWanted && Boolean.TRUE.equals(config.clientAuthenticationRequired)) {
				throw new IllegalArgumentException("client authentication is already required!");
			}
			config.clientAuthenticationWanted = authWanted;
			return this;
		}

		/**
		 * Sets whether the connector requires DTLS clients to authenticate
		 * during the handshake.
		 * 
		 * The default is {@code true}. If
		 * {@link #setClientAuthenticationWanted(boolean)} is set to
		 * {@code true}, the default is {@code false}. Only used by the DTLS
		 * server side.
		 * 
		 * @param authRequired {@code true}, if clients need to authenticate
		 * @return this builder for command chaining
		 * @throws IllegalStateException if configuration is for client only
		 * @throws IllegalArgumentException if authWanted is {@code true}, but
		 *             {@link #setClientAuthenticationWanted(boolean)} was set
		 *             to {@code true} before.
		 */
		public Builder setClientAuthenticationRequired(boolean authRequired) {
			if (Boolean.TRUE.equals(config.clientOnly)) {
				throw new IllegalStateException("client authentication is not supported for client only!");
			}
			if (authRequired && Boolean.TRUE.equals(config.clientAuthenticationWanted)) {
				throw new IllegalArgumentException("client authentication is already wanted!");
			}
			config.clientAuthenticationRequired = authRequired;
			return this;
		}

		/**
		 * Sets the cipher suite selector.
		 * <p>
		 * The connector will use these selector to determine the cipher suite
		 * and parameters during the handshake.
		 * 
		 * @param cipherSuiteSelector the cipher suite selector. Default
		 *            ({@link DefaultCipherSuiteSelector}.
		 * @return this builder for command chaining
		 * 
		 * @since 2.3
		 */
		public Builder setCipherSuiteSelector(CipherSuiteSelector cipherSuiteSelector) {
			config.cipherSuiteSelector = cipherSuiteSelector;
			return this;
		}

		/**
		 * Sets the preselected cipher suites for the connector.
		 * 
		 * If no supported cipher suites are provided, consider only this subset
		 * of {@link CipherSuite} to be automatically selected as supported
		 * cipher suites depending on other setting (e.g. if settings allow only
		 * PSK, only PSK compatible cipher suite from this list will be
		 * selected).
		 * 
		 * Not used, if supported cipher suites are provided.
		 * 
		 * @param cipherSuites the preselected cipher suites
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the list is empty,
		 *             "TLS_NULL_WITH_NULL_NULL" is contained, or the use of
		 *             HELLO_VERIFY_REQUEST is disabled and no PSK cipher suite
		 *             is contained.
		 * @since 2.5
		 */
		public Builder setPreselectedCipherSuites(CipherSuite... cipherSuites) {
			if (cipherSuites == null) {
				config.preselectedCipherSuites = null;
				return this;
			} else {
				return setPreselectedCipherSuites(Arrays.asList(cipherSuites));
			}
		}

		/**
		 * Sets the preselected cipher suites for the connector.
		 * 
		 * If no supported cipher suites are provided, consider only this subset
		 * of {@link CipherSuite} to be automatically selected as supported
		 * cipher suites depending on other setting (e.g. if settings allow only
		 * PSK, only PSK compatible cipher suite from this list will be
		 * selected).
		 * 
		 * Not used, if supported cipher suites are provided.
		 * 
		 * @param cipherSuites the preselected cipher suites
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the list is empty,
		 *             "TLS_NULL_WITH_NULL_NULL" is contained, or the use of
		 *             HELLO_VERIFY_REQUEST is disabled and no PSK cipher suite
		 *             is contained.
		 * @since 2.5
		 */
		public Builder setPreselectedCipherSuites(List<CipherSuite> cipherSuites) {
			if (cipherSuites == null) {
				config.preselectedCipherSuites = null;
			} else if (cipherSuites.isEmpty()) {
				throw new IllegalArgumentException("Connector must preselect at least one cipher suite");
			} else if (cipherSuites.contains(CipherSuite.TLS_NULL_WITH_NULL_NULL)) {
				throw new IllegalArgumentException("NULL Cipher Suite is not supported by connector");
			} else if (Boolean.FALSE.equals(config.useHelloVerifyRequestForPsk)) {
				if (!CipherSuite.containsPskBasedCipherSuite(cipherSuites)) {
					throw new IllegalArgumentException(
							"HELLO_VERIFY_REQUEST disabled, requires at least on PSK cipher suite!");
				}
			}
			config.preselectedCipherSuites = cipherSuites;
			return this;
		}

		/**
		 * Sets the preselected cipher suites for the connector.
		 * 
		 * If no supported cipher suites are provided, consider only this subset
		 * of {@link CipherSuite} to be automatically selected as supported
		 * cipher suites depending on other setting (e.g. if settings allow only
		 * PSK, only PSK compatible cipher suite from this list will be
		 * selected).
		 * 
		 * Not used, if supported cipher suites are provided.
		 * 
		 * @param cipherSuites the names of the preselected cipher suites
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if at least one name is not
		 *             available, "TLS_NULL_WITH_NULL_NULL" is contained, or the
		 *             use of HELLO_VERIFY_REQUEST is disabled and no PSK cipher
		 *             suite is contained.
		 * @since 2.5
		 */
		public Builder setPreselectedCipherSuites(String... cipherSuites) {
			if (cipherSuites == null) {
				config.preselectedCipherSuites = null;
				return this;
			} else {
				List<CipherSuite> suites = CipherSuite.getTypesByNames(cipherSuites);
				return setPreselectedCipherSuites(suites);
			}
		}

		/**
		 * Sets the cipher suites supported by the connector.
		 * <p>
		 * The connector will use these cipher suites (in exactly the same
		 * order) during the DTLS handshake when negotiating a cipher suite with
		 * a peer.
		 * 
		 * @param cipherSuites the supported cipher suites in the order of
		 *            preference
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given array is {@code null}
		 * @throws IllegalArgumentException if the given array is empty,
		 *             contains {@link CipherSuite#TLS_NULL_WITH_NULL_NULL},
		 *             contains a cipher suite, not supported by the JVM,
		 *             violates the
		 *             {@link #setRecommendedCipherSuitesOnly(boolean)} setting,
		 *             or the use of HELLO_VERIFY_REQUEST is disabled and no PSK
		 *             cipher suite is contained.
		 */
		public Builder setSupportedCipherSuites(CipherSuite... cipherSuites) {
			if (cipherSuites == null) {
				throw new NullPointerException("Connector must support at least one cipher suite");
			}
			return setSupportedCipherSuites(Arrays.asList(cipherSuites));
		}

		/**
		 * Sets the cipher suites supported by the connector.
		 * <p>
		 * The connector will use these cipher suites (in exactly the same
		 * order) during the DTLS handshake when negotiating a cipher suite with
		 * a peer.
		 * 
		 * @param cipherSuites the supported cipher suites in the order of
		 *            preference
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given list is {@code null}
		 * @throws IllegalArgumentException if the given list is empty, contains
		 *             {@link CipherSuite#TLS_NULL_WITH_NULL_NULL}, contains a
		 *             cipher suite, not supported by the JVM, violates the
		 *             {@link #setRecommendedCipherSuitesOnly(boolean)} setting,
		 *             or the use of HELLO_VERIFY_REQUEST is disabled and no PSK
		 *             cipher suite is contained.
		 */
		public Builder setSupportedCipherSuites(List<CipherSuite> cipherSuites) {
			if (cipherSuites == null) {
				throw new NullPointerException("Connector must support at least one cipher suite");
			}
			if (cipherSuites.isEmpty()) {
				throw new IllegalArgumentException("Connector must support at least one cipher suite");
			}
			if (cipherSuites.contains(CipherSuite.TLS_NULL_WITH_NULL_NULL)) {
				throw new IllegalArgumentException("NULL Cipher Suite is not supported by connector");
			}
			if (Boolean.FALSE.equals(config.useHelloVerifyRequestForPsk)) {
				if (!CipherSuite.containsPskBasedCipherSuite(cipherSuites)) {
					throw new IllegalArgumentException(
							"HELLO_VERIFY_REQUEST disabled, requires at least on PSK cipher suite!");
				}
			}
			if (config.recommendedCipherSuitesOnly == null || config.recommendedCipherSuitesOnly) {
				verifyRecommendedCipherSuitesOnly(cipherSuites);
			}
			for (CipherSuite cipherSuite : cipherSuites) {
				if (!cipherSuite.isSupported()) {
					throw new IllegalArgumentException("cipher-suites " + cipherSuite + " is not supported by JVM!");
				}
			}

			config.supportedCipherSuites = cipherSuites;
			return this;
		}

		/**
		 * Sets the cipher suites supported by the connector.
		 * <p>
		 * The connector will use these cipher suites (in exactly the same
		 * order) during the DTLS handshake when negotiating a cipher suite with
		 * a peer.
		 * 
		 * @param cipherSuites the names of supported cipher suites in the order
		 *            of preference (see <a href=
		 *            "https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml#tls-parameters-4"
		 *            target="_blank"> IANA registry</a> for a list of cipher
		 *            suite names)
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given array is {@code null}
		 * @throws IllegalArgumentException if the given array is empty,
		 *             contains {@link CipherSuite#TLS_NULL_WITH_NULL_NULL},
		 *             contains a cipher suite, not supported by the JVM,
		 *             contains a name, which is not supported, violates the
		 *             {@link #setRecommendedCipherSuitesOnly(boolean)} setting,
		 *             or the use of HELLO_VERIFY_REQUEST is disabled and no PSK
		 *             cipher suite is contained.
		 */
		public Builder setSupportedCipherSuites(String... cipherSuites) {
			if (cipherSuites == null) {
				throw new NullPointerException("Connector must support at least one cipher suite");
			}
			List<CipherSuite> suites = CipherSuite.getTypesByNames(cipherSuites);
			return setSupportedCipherSuites(suites);
		}

		/**
		 * Sets the signature algorithms supported by the connector.
		 * <p>
		 * The connector will use these signature algorithms (in exactly the
		 * same order) during the DTLS handshake.
		 * 
		 * @param supportedSignatureAlgorithms the supported signature
		 *            algorithms in the order of preference. No arguments, if no
		 *            specific extension is to be used for a client, and the
		 *            server uses {@link SignatureAndHashAlgorithm#DEFAULT}.
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the list violates the
		 *             {@link #setRecommendedSignatureAndHashAlgorithmsOnly(boolean)}
		 *             setting.
		 * @since 3.0 (reports recommendedSignatureAndHashAlgorithmsOnly violations)
		 */
		public Builder setSupportedSignatureAlgorithms(SignatureAndHashAlgorithm... supportedSignatureAlgorithms) {
			if (supportedSignatureAlgorithms == null) {
				config.supportedSignatureAlgorithms = null;
				return this;
			} else {
				return setSupportedSignatureAlgorithms(Arrays.asList(supportedSignatureAlgorithms));
			}
		}

		/**
		 * Sets the signature algorithms supported by the connector.
		 * <p>
		 * The connector will use these signature algorithms (in exactly the
		 * same order) during the DTLS handshake.
		 * 
		 * @param supportedSignatureAlgorithms the list of supported signature
		 *            algorithms in the order of preference. Empty, if no
		 *            specific extension is to be used for a client, and the
		 *            server uses {@link SignatureAndHashAlgorithm#DEFAULT}.
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the list violates the
		 *             {@link #setRecommendedSignatureAndHashAlgorithmsOnly(boolean)}
		 *             setting.
		 * @since 3.0 (reports recommendedSignatureAndHashAlgorithmsOnly violations)
		 */
		public Builder setSupportedSignatureAlgorithms(List<SignatureAndHashAlgorithm> supportedSignatureAlgorithms) {
			if (supportedSignatureAlgorithms != null && (config.recommendedSignatureAndHashAlgorithmsOnly == null
					|| config.recommendedSignatureAndHashAlgorithmsOnly)) {
				verifyRecommendedSignatureAndHashAlgorithmsOnly(supportedSignatureAlgorithms);
			}
			config.supportedSignatureAlgorithms = supportedSignatureAlgorithms;
			return this;
		}

		/**
		 * Sets the signature algorithms supported by the connector.
		 * <p>
		 * The connector will use these signature algorithms (in exactly the
		 * same order) during the DTLS handshake.
		 * 
		 * @param supportedSignatureAlgorithms the list of supported signature
		 *            algorithm names in the order of preference. Empty, if no
		 *            specific extension is to be used for a client, and the
		 *            server uses {@link SignatureAndHashAlgorithm#DEFAULT}.
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the list violates the
		 *             {@link #setRecommendedSignatureAndHashAlgorithmsOnly(boolean)}
		 *             setting or not supported signature and algorithms are
		 *             contained in the list.
		 * @see SignatureAndHashAlgorithm#valueOf(String)
		 * @since 3.0 (reports recommendedSignatureAndHashAlgorithmsOnly violations)
		 */
		public Builder setSupportedSignatureAlgorithms(String... supportedSignatureAlgorithms) {
			List<SignatureAndHashAlgorithm> list = null;
			if (supportedSignatureAlgorithms != null) {
				list = new ArrayList<SignatureAndHashAlgorithm>(supportedSignatureAlgorithms.length);
				for (int i = 0; i < supportedSignatureAlgorithms.length; i++) {
					SignatureAndHashAlgorithm signatureAndHashAlgorithm = SignatureAndHashAlgorithm
							.valueOf(supportedSignatureAlgorithms[i]);
					if (signatureAndHashAlgorithm != null) {
						list.add(signatureAndHashAlgorithm);
					} else {
						throw new IllegalArgumentException(
								String.format("Signature and hash algorithm [%s] is not (yet) supported",
										supportedSignatureAlgorithms[i]));
					}
				}
			}
			return setSupportedSignatureAlgorithms(list);
		}

		/**
		 * Sets the groups (curves) supported by the connector.
		 * <p>
		 * The connector will use these supported groups (in exactly the same
		 * order) during the DTLS handshake when negotiating a curve with a
		 * peer. According
		 * <a href="https://tools.ietf.org/html/rfc8422#page-11" target= "_blank">RFC 8422, 5.1.
		 * Client Hello Extensions, Actions of the receiver</a> This affects
		 * both, curves for ECDH and the certificates for ECDSA.
		 * 
		 * @param supportedGroups the supported groups (curves) in the order of
		 *            preference
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given array is {@code null}
		 * @throws IllegalArgumentException if the given array is empty,
		 *             contains a group (curve), not supported by the JVM, or
		 *             violates the
		 *             {@link #setRecommendedCipherSuitesOnly(boolean)} setting.
		 * 
		 * @since 2.3
		 */
		public Builder setSupportedGroups(SupportedGroup... supportedGroups) {
			if (supportedGroups == null) {
				throw new NullPointerException("Connector must support at least one group (curve)");
			}
			return setSupportedGroups(Arrays.asList(supportedGroups));
		}

		/**
		 * Sets the groups (curves) supported by the connector.
		 * <p>
		 * The connector will use these supported groups (in exactly the same
		 * order) during the DTLS handshake when negotiating a curve with a
		 * peer. According
		 * <a href="https://tools.ietf.org/html/rfc8422#page-11" target= "_blank">RFC 8422, 5.1.
		 * Client Hello Extensions, Actions of the receiver</a> This affects
		 * both, curves for ECDH and the certificates for ECDSA.
		 * 
		 * @param supportedGroups the supported groups (curves) in the order of
		 *            preference
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given list is {@code null}
		 * @throws IllegalArgumentException if the given list is empty, contains
		 *             a group (curve), not supported by the JVM, or violates
		 *             the {@link #setRecommendedCipherSuitesOnly(boolean)}
		 *             setting.
		 * 
		 * @since 2.3
		 */
		public Builder setSupportedGroups(List<SupportedGroup> supportedGroups) {
			if (supportedGroups == null) {
				throw new NullPointerException("Connector must support at least one group (curve)");
			}
			if (supportedGroups.isEmpty()) {
				throw new IllegalArgumentException("Connector must support at least one group (curve)");
			}
			if (config.recommendedSupportedGroupsOnly == null || config.recommendedSupportedGroupsOnly) {
				verifyRecommendedSupportedGroupsOnly(supportedGroups);
			}
			for (SupportedGroup group : supportedGroups) {
				if (!group.isUsable()) {
					throw new IllegalArgumentException("curve " + group.name() + " is not supported by JVM!");
				}
			}

			config.supportedGroups = supportedGroups;
			return this;
		}

		/**
		 * Sets the groups (curves) supported by the connector.
		 * <p>
		 * The connector will use these supported groups (in exactly the same
		 * order) during the DTLS handshake when negotiating a curve with a
		 * peer. According
		 * <a href="https://tools.ietf.org/html/rfc8422#page-11" target="_blank">
		 * RFC 8422, 5.1. Client Hello Extensions, Actions of the receiver</a>
		 * this affects both, curves for ECDH and the certificates for ECDSA.
		 * 
		 * @param supportedGroups the names of supported groups (curves) in the
		 *            order of preference (see <a href=
		 *            "https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml#tls-parameters-8"
		 *            target="_blank"> IANA registry</a> for a list of supported
		 *            group names)
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given array is {@code null}
		 * @throws IllegalArgumentException if the given array is empty,
		 *             contains a group (curve), not supported by the JVM, or
		 *             violates the
		 *             {@link #setRecommendedCipherSuitesOnly(boolean)} setting.
		 * @since 2.3
		 */
		public Builder setSupportedGroups(String... supportedGroups) {
			if (supportedGroups == null) {
				throw new NullPointerException("Connector must support at least one supported group (curve)");
			}
			List<SupportedGroup> groups = new ArrayList<>(supportedGroups.length);
			for (int i = 0; i < supportedGroups.length; i++) {
				SupportedGroup knownGroup = SupportedGroup.valueOf(supportedGroups[i]);
				if (knownGroup != null) {
					groups.add(knownGroup);
				} else {
					throw new IllegalArgumentException(
							String.format("Group (curve) [%s] is not (yet) supported", supportedGroups[i]));
				}
			}
			return setSupportedGroups(groups);
		}

		/**
		 * Activate/Deactivate experimental feature: Stop retransmission at
		 * first received handshake message.
		 * 
		 * @param activate Set it to true if retransmissions should be stopped
		 *            as soon as we receive a handshake message
		 * @return this builder for command chaining
		 */
		public Builder setEarlyStopRetransmission(boolean activate) {
			config.earlyStopRetransmission = activate;
			return this;
		}

		/**
		 * Sets the (starting) time to wait before a handshake package gets re-transmitted.
		 * 
		 * On each retransmission, the time is doubled.
		 * 
		 * @param timeout the time in milliseconds
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the given timeout is negative
		 */
		public Builder setRetransmissionTimeout(int timeout) {
			if (timeout < 0) {
				throw new IllegalArgumentException("Retransmission timeout must not be negative");
			}
			config.retransmissionTimeout = timeout;
			return this;
		}

		/**
		 * Sets the additional (starting) time to wait before a handshake
		 * package gets re-transmitted, when the other peer is expected to
		 * perform ECC calculations.
		 * 
		 * ECC calculations may be time intensive, especially for smaller
		 * micro-controllers without ecc-hardware support. The additional
		 * timeout prevents Californium from resending a flight too early. The
		 * extra time is used for the DTLS-client, if a ECDSA or ECDHE cipher
		 * suite is proposed, and for the DTLS-server, if a ECDSA or ECDHE
		 * cipher suite is selected.
		 * 
		 * This timeout is added to {@link #getRetransmissionTimeout()} and on
		 * each retransmission, the resulting time is doubled.
		 * 
		 * @param timeout the additional time in milliseconds. Default is
		 *            {@code 0} milliseconds.
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the given timeout is negative
		 * @since 3.0
		 */
		public Builder setAdditionalTimeoutForEcc(int timeout) {
			if (timeout < 0) {
				throw new IllegalArgumentException("Additional timeout for ECC must not be negative");
			}
			config.additionalTimeoutForEcc = timeout;
			return this;
		}

		/**
		 * Sets the advanced key store to use for authenticating clients based
		 * on a pre-shared key.
		 * 
		 * If used together with
		 * {@link #setCertificateIdentityProvider(CertificateProvider)} the default
		 * preference uses the certificate based cipher suites. To change that,
		 * use {@link #setSupportedCipherSuites(CipherSuite...)} or
		 * {@link #setSupportedCipherSuites(String...)}.
		 * 
		 * @param advancedPskStore the advanced key store
		 * @return this builder for command chaining
		 * @since 2.3
		 */
		public Builder setAdvancedPskStore(AdvancedPskStore advancedPskStore) {
			config.advancedPskStore = advancedPskStore;
			return this;
		}

		/**
		 * Sets the connector's certificate identifying provider.
		 * <p>
		 * Please ensure, that you setup
		 * {@link #setAdvancedCertificateVerifier(NewAdvancedCertificateVerifier)},
		 * if you want to trust the other peers.
		 * 
		 * If used together with {@link #setAdvancedPskStore(AdvancedPskStore)},
		 * the default preference uses this certificate based cipher suites. To
		 * change that, use {@link #setSupportedCipherSuites(CipherSuite...)} or
		 * {@link #setSupportedCipherSuites(String...)}.
		 * 
		 * For cases, where only a single certificate based identity is used, a
		 * instance of {@link SingleCertificateProvider} may be provided.
		 * 
		 * @param certificateIdentityProvider the certificate identity provider
		 * @return this builder for command chaining
		 * @see #setAdvancedCertificateVerifier(NewAdvancedCertificateVerifier)
		 */
		public Builder setCertificateIdentityProvider(CertificateProvider certificateIdentityProvider) {
			config.certificateIdentityProvider = certificateIdentityProvider;
			return this;
		}

		/**
		 * Sets the logic in charge of validating a X.509 certificate chain.
		 *
		 * Here are a few use cases where a custom implementation would be
		 * needed:
		 * <ul>
		 * <li>client certificate authentication based on a dynamic trusted CA
		 * <li>revocation not provided by the default implementation (e.g. OCSP)
		 * <li>cipher suites restriction per client
		 * </ul>
		 * 
		 * @param verifier new advanced certificate verifier
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given certificate verifier is
		 *             {@code null}
		 * @since 2.5
		 */
		public Builder setAdvancedCertificateVerifier(NewAdvancedCertificateVerifier verifier) {
			if (verifier == null) {
				throw new NullPointerException("CertificateVerifier must not be null");
			}
			config.advancedCertificateVerifier = verifier;
			return this;
		}

		/**
		 * Sets a supplier of application level information for an authenticated peer's identity.
		 * 
		 * @param supplier The supplier.
		 * @return this builder for command chaining.
		 * @throws NullPointerException if supplier is {@code null}.
		 */
		public Builder setApplicationLevelInfoSupplier(ApplicationLevelInfoSupplier supplier) {
			if (supplier == null) {
				throw new NullPointerException("Supplier must not be null");
			}
			config.applicationLevelInfoSupplier = supplier;
			return this;
		}

		/**
		 * Set maximum number of deferred processed outgoing application data
		 * messages.
		 * 
		 * Application data messages sent during a handshake may be dropped or
		 * processed deferred after the handshake. Set this to limit the maximum
		 * number of messages, which are intended to be processed deferred. If
		 * more messages are sent, these messages are dropped.
		 * 
		 * @param maxDeferredProcessedOutgoingApplicationDataMessages maximum
		 *            number of deferred processed messages
		 * @return this builder for command chaining.
		 * @throws IllegalArgumentException if the given limit is &lt; 0.
		 */
		public Builder setMaxDeferredProcessedOutgoingApplicationDataMessages(
				int maxDeferredProcessedOutgoingApplicationDataMessages) {
			if (maxDeferredProcessedOutgoingApplicationDataMessages < 0) {
				throw new IllegalArgumentException(
						"Max deferred processed outging application data messages must not be negative!");
			}
			config.maxDeferredProcessedOutgoingApplicationDataMessages = maxDeferredProcessedOutgoingApplicationDataMessages;
			return this;
		}

		/**
		 * Set maximum size of deferred processed incoming records.
		 * 
		 * Handshake records with future handshake message sequence number or
		 * records with future epochs received during a handshake may be dropped
		 * or processed deferred. Set this to limit the maximum size of all
		 * records, which are intended to be processed deferred. If more records
		 * are received, these records are dropped.
		 * 
		 * @param maxDeferredProcessedIncomingRecordsSize maximum size of all
		 *            deferred handshake records
		 * @return this builder for command chaining.
		 * @throws IllegalArgumentException if the given limit is &lt; 0.
		 */
		public Builder setMaxDeferredProcessedIncomingRecordsSize(int maxDeferredProcessedIncomingRecordsSize) {
			if (maxDeferredProcessedIncomingRecordsSize < 0) {
				throw new IllegalArgumentException(
						"Max deferred processed incoming records size must not be negative!");
			}
			config.maxDeferredProcessedIncomingRecordsSize = maxDeferredProcessedIncomingRecordsSize;
			return this;
		}

		/**
		 * Sets the maximum number of active connections the connector should support.
		 * <p>
		 * An <em>active</em> connection is a connection that has been used within the
		 * last <em>staleConnectionThreshold</em> seconds. After that it is considered
		 * to be <em>stale</em>.
		 * <p>
		 * Once the maximum number of active connections is reached, new connections will
		 * only be accepted by the connector, if <em>stale</em> connections exist (which will
		 * be evicted one-by-one on an oldest-first basis).
		 * <p>
		 * The default value of this property is {@link DtlsConnectorConfig#DEFAULT_MAX_CONNECTIONS}.
		 * 
		 * @param maxConnections The maximum number of active connections to support.
		 * @return this builder for command chaining.
		 * @throws IllegalArgumentException if the given limit is &lt; 1.
		 * @see #setStaleConnectionThreshold(long)
		 */
		public Builder setMaxConnections(final int maxConnections) {
			if (maxConnections < 1) {
				throw new IllegalArgumentException("Max connections must be at least 1");
			}
			config.maxConnections = maxConnections;
			return this;
		}

		/**
		 * Sets the maximum number of seconds without any data being exchanged before a connection
		 * is considered <em>stale</em>.
		 * <p>
		 * Once a connection becomes stale, it is eligible for eviction when a peer wants to establish a
		 * new connection and the connector already has <em>maxConnections</em> connections with peers
		 * established. Note that a connection is no longer considered stale, once data is being exchanged
		 * over it before it got evicted.
		 * 
		 * @param threshold The number of seconds.
		 * @return this builder for command chaining.
		 * @throws IllegalArgumentException if the given threshold is &lt; 1.
		 * @see #setMaxConnections(int)
		 */
		public Builder setStaleConnectionThreshold(final long threshold) {
			if (threshold < 1) {
				throw new IllegalArgumentException("Threshold must be at least 1 second");
			}
			config.staleConnectionThreshold = threshold;
			return this;
		}

		/**
		 * Sets the connection id generator.
		 * 
		 * @param connectionIdGenerator connection id generator. {@code null}
		 *            for not supported. The generator may only support the use
		 *            of a connection id without using it by itself. In that
		 *            case {@link ConnectionIdGenerator#useConnectionId()} must
		 *            return {@code false}.
		 * @return this builder for command chaining.
		 */
		public Builder setConnectionIdGenerator(ConnectionIdGenerator connectionIdGenerator) {
			config.connectionIdGenerator = connectionIdGenerator;
			return this;
		}

		/**
		 * Set the number of thread which should be used to handle DTLS
		 * connection.
		 * <p>
		 * The default value is 6 * <em>#(CPU cores)</em>.
		 * 
		 * @param threadCount the number of threads.
		 * @return this builder for command chaining.
		 */
		public Builder setConnectionThreadCount(int threadCount) {
			config.connectionThreadCount = threadCount;
			return this;
		}

		/**
		 * Set the number of thread which should be used to receive
		 * datagrams from the socket.
		 * <p>
		 * The default value is half of <em>#(CPU cores)</em>.
		 * 
		 * @param threadCount the number of threads.
		 * @return this builder for command chaining.
		 */
		public Builder setReceiverThreadCount(int threadCount) {
			config.receiverThreadCount = threadCount;
			return this;
		}

		/**
		 * Set the timeout of automatic session resumption in milliseconds.
		 * <p>
		 * The default value is {@code null}, for no automatic session
		 * resumption. The configured value may be overridden by the endpoint
		 * context attribute {@link DtlsEndpointContext#KEY_RESUMPTION_TIMEOUT}.
		 * 
		 * @param timeoutInMillis the number of milliseconds. Usually values
		 *            around 30000 milliseconds are useful, depending on the
		 *            setup of NATS on the path. Smaller timeouts are only
		 *            useful for unit test, they would trigger too many
		 *            resumption handshakes.
		 * @return this builder for command chaining.
		 * @throws IllegalArgumentException if the timeout is below 1
		 *             millisecond
		 */
		public Builder setAutoResumptionTimeoutMillis(Long timeoutInMillis) {
			if (timeoutInMillis != null && timeoutInMillis < 1) {
				throw new IllegalArgumentException("auto resumption timeout must not below 1!");
			}
			config.autoResumptionTimeoutMillis = timeoutInMillis;
			return this;
		}

		/**
		 * Sets whether the connector should support the use of the TLS
		 * <a href="https://tools.ietf.org/html/rfc6066#section-3" target="_blank">
		 * Server Name Indication extension</a> in the DTLS handshake.
		 * <p>
		 * The default value of this property is {@code null}. If this property
		 * is not set explicitly, then the {@link Builder#build()} method
		 * will set it to {@code true}.
		 * 
		 * @param flag {@code true} if SNI should be used.
		 * @return this builder for command chaining.
		 */
		public Builder setSniEnabled(boolean flag) {
			config.sniEnabled = flag;
			return this;
		}

		/**
		 * Sets the <em>Extended Master Secret</em> TLS extension mode.
		 * 
		 * <p>
		 * See <a href="https://tools.ietf.org/html/rfc7627" target="_blank">RFC 7627, Extended
		 * Master Secret extension</a> and {@link ExtendedMasterSecretMode} for
		 * details.
		 * </p>
		 * <p>
		 * The default value of this property is {@code null}. If this property
		 * is not set explicitly using
		 * {@link Builder#setExtendedMasterSecretMode(ExtendedMasterSecretMode)},
		 * then the {@link Builder#build()} method will set it to
		 * {@link ExtendedMasterSecretMode#ENABLED}.
		 * </p>
		 * 
		 * @param mode the extended master secret mode
		 * @return this builder for command chaining.
		 * @since 3.0
		 */
		public Builder setExtendedMasterSecretMode(ExtendedMasterSecretMode mode) {
			config.extendedMasterSecretMode = mode;
			return this;
		}

		/**
		 * Sets threshold in percent of {@link #setMaxConnections(int)}, whether
		 * a HELLO_VERIFY_REQUEST should be used also for session resumption.
		 * <p>
		 * <b>Note:</b> a value larger than 0 will call the {@link ResumptionVerifier}.
		 * If that implementation is expensive, please ensure, that this value
		 * is configured with {@code 0}. Otherwise, CLIENT_HELLOs with invalid
		 * session ids may be spoofed and gets too expensive.
		 * </p>
		 * @param threshold 0 := always use HELLO_VERIFY_REQUEST, 1 ... 100 :=
		 *            dynamically determine to use HELLO_VERIFY_REQUEST. Default
		 *            is based on
		 *            {@link DtlsConnectorConfig#DEFAULT_VERIFY_PEERS_ON_RESUMPTION_THRESHOLD_IN_PERCENT}
		 * @return this builder for command chaining.
		 * @throws IllegalStateException if the HELLO_VERIFY_REQUEST is disabled
		 * @throws IllegalArgumentException if threshold is not between 0 and
		 *             100
		 */
		public Builder setVerifyPeersOnResumptionThreshold(int threshold) {
			if (Boolean.FALSE.equals(config.useHelloVerifyRequest)) {
				throw new IllegalStateException("HELLO_VERIFY_REQUEST is already disabled!");
			}
			if (threshold < 0 || threshold > 100) {
				throw new IllegalArgumentException("threshold must be between 0 and 100, but is " + threshold + "!");
			}
			config.verifyPeersOnResumptionThreshold = threshold;
			return this;
		}

		/**
		 * Enable/disable the server's HELLO_VERIFY_REQUEST, if peers shares at
		 * least one PSK based cipher suite.
		 * <p>
		 * <b>Note:</b> it is not recommended to disable the
		 * HELLO_VERIFY_REQUEST! See
		 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
		 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>.
		 * </p>
		 * To limit the amplification, the peers must share PSK cipher suites to
		 * by pass that check. If only certificate based cipher suites are
		 * shared, the HELLO_VERIFY_REQUEST will still be used.
		 * 
		 * @param enable {@code true}, if a HELLO_VERIFY_REQUEST should be send
		 *            to the client, {@code false}, if no HELLO_VERIFY_REQUEST
		 *            is used.
		 * @return this builder for command chaining.
		 * @see HelloVerifyRequest
		 * @throws IllegalStateException if the configuration is for client
		 *             only.
		 * @throws IllegalArgumentException if a verify peers on resumption
		 *             threshold is used, or configuration doesn't contain a PSK
		 *             based cipher suite.
		 * @since 3.0
		 */
		public Builder setUseHelloVerifyRequestForPsk(boolean enable) {
			if (Boolean.TRUE.equals(config.clientOnly)) {
				throw new IllegalStateException("HELLO_VERIFY_REQUEST usage is not supported for client only!");
			}
			if (Boolean.FALSE.equals(config.useHelloVerifyRequest) && enable) {
				throw new IllegalStateException("HELLO_VERIFY_REQUEST is generally disabled!");
			}
			if (!enable) {
				if (config.supportedCipherSuites != null) {
					if (!CipherSuite.containsPskBasedCipherSuite(config.supportedCipherSuites)) {
						throw new IllegalArgumentException(
								"No PSK cipher suite selected, HELLO_VERIFY_REQUEST can not be disabled!");
					}
				}
			}
			config.useHelloVerifyRequestForPsk = enable;
			return this;
		}

		/**
		 * Generally enable/disable the server's HELLO_VERIFY_REQUEST.
		 * <p>
		 * <b>Note:</b> it is strongly not recommended to disable the
		 * HELLO_VERIFY_REQUEST for certificates! That creates a large
		 * amplification! See
		 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
		 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>.
		 * </p>
		 * 
		 * @param enable {@code true}, if a HELLO_VERIFY_REQUEST should be send
		 *            to the client, {@code false}, if no HELLO_VERIFY_REQUEST
		 *            is used.
		 * @return this builder for command chaining.
		 * @see HelloVerifyRequest
		 * @see #setUseHelloVerifyRequestForPsk(boolean)
		 * @throws IllegalStateException if the configuration is for client
		 *             only.
		 * @throws IllegalArgumentException if a verify peers on resumption
		 *             threshold is used, or configuration doesn't contain a PSK
		 *             based cipher suite.
		 * @since 3.0
		 */
		public Builder setUseHelloVerifyRequest(boolean enable) {
			if (Boolean.TRUE.equals(config.clientOnly)) {
				throw new IllegalStateException("HELLO_VERIFY_REQUEST usage is not supported for client only!");
			}
			if (!enable) {
				if (Boolean.TRUE.equals(config.useHelloVerifyRequestForPsk)) {
					throw new IllegalStateException("HELLO_VERIFY_REQUEST is enabled for PSK!");
				}
				if (config.verifyPeersOnResumptionThreshold != null) {
					throw new IllegalArgumentException("Verify peers on resumption threshold is already set!");
				}
			}
			config.useHelloVerifyRequest = enable;
			return this;
		}

		/**
		 * Set whether session id is used by this server or not.
		 * 
		 * @param flag {@code true} if session id is used by this server,
		 *            {@code false}, if not. Default {@code true}.
		 * @return this builder for command chaining.
		 * @throws IllegalArgumentException if no session id should be used and
		 *             the configuration is for client only.
		 * @since 3.0 (was setNoServerSessionId with inverse logic)
		 */
		public Builder setUseServerSessionId(boolean flag) {
			if (Boolean.TRUE.equals(config.clientOnly) && !flag) {
				throw new IllegalArgumentException("not applicable for client only!");
			}
			config.useServerSessionId = flag;
			return this;
		}

		/**
		 * Use anti replay filter.
		 * 
		 * @param enable {@code true} to enable filter. Default {@code true}.
		 * @return this builder for command chaining.
		 * @throws IllegalArgumentException if window filter is active.
		 * @see <a href="https://tools.ietf.org/html/rfc6347#section-4.1.2.6" target="_blank">RFC6347, 4.1.2.6 Anti-Replay</a>
		 */
		public Builder setUseAntiReplayFilter(boolean enable) {
			if (enable && config.useExtendedWindowFilter != null && config.useExtendedWindowFilter != 0) {
				throw new IllegalArgumentException("Window filter is active!");
			}
			config.useAntiReplayFilter = enable;
			return this;
		}

		/**
		 * Use extended window filter.
		 * 
		 * The value will be subtracted from to lower receive window boundary. A
		 * value of {@code -1} will set that calculated value to {@code 0}.
		 * Messages between lower receive window boundary and that calculated
		 * value will pass the filter, for other messages the filter is applied.
		 * 
		 * @param level value to extend lower receive window boundary, {@code 0}
		 *            to disable the extended lower boundary. For backwards
		 *            compatibility use {@code -1}, to extend the lower boundary
		 *            down to {@code 0}, Default {@code 0} for disabled.
		 * @return this builder for command chaining.
		 * @throws IllegalArgumentException if anti replay window filter is
		 *             active.
		 * @see <a href="https://tools.ietf.org/html/rfc6347#section-4.1.2.6" target="_blank">RFC6347, 4.1.2.6 Anti-Replay</a>
		 * @since 2.4
		 */
		public Builder setUseExtendedWindowFilter(int level) {
			if (level != 0 && Boolean.TRUE.equals(config.useAntiReplayFilter)) {
				throw new IllegalArgumentException("Anti replay filter is active!");
			}
			config.useExtendedWindowFilter = level;
			return this;
		}

		/**
		 * Use filter to update the ip-address from DTLS 1.2 CID records only
		 * for newer records based on epoch/sequence_number.
		 * 
		 * Only used, if a connection ID generator
		 * {@link #setConnectionIdGenerator(ConnectionIdGenerator)} is provided,
		 * which "uses" CID. If the "anti-replay-filter is switched off, it's
		 * not recommended to switch this off also!
		 * 
		 * @param enable {@code true} to enable filter, {@code false} to disable
		 *            filter. Default {@code true}.
		 * @return this builder for command chaining.
		 */
		public Builder setCidUpdateAddressOnNewerRecordFilter(boolean enable) {
			config.useCidUpdateAddressOnNewerRecordFilter = enable;
			return this;
		}

		/**
		 * Use truncated certificate paths for client's certificate message.
		 * 
		 * Truncate certificate path according the received certificate
		 * authorities in the {@link CertificateRequest} for the client's
		 * {@link CertificateMessage}.
		 * 
		 * @param enable {@code true} to truncate the certificate path according
		 *            the received certificate authorities. Default
		 *            {@code true}.
		 * @return this builder for command chaining.
		 * @since 2.1
		 */
		public Builder setUseTruncatedCertificatePathForClientsCertificateMessage(boolean enable) {
			config.useTruncatedCertificatePathForClientsCertificateMessage = enable;
			return this;
		}

		/**
		 * Use truncated certificate paths for validation.
		 * 
		 * Truncate certificate path according the available trusted
		 * certificates before validation.
		 * 
		 * @param enable {@code true} to truncate the certificate path according
		 *            the available trusted certificates. Default {@code true}.
		 * @return this builder for command chaining.
		 * @since 2.1
		 */
		public Builder setUseTruncatedCertificatePathForValidation(boolean enable) {
			config.useTruncatedCertificatePathForValidation = enable;
			return this;
		}

		/**
		 * Set instance logging tag.
		 * 
		 * @param tag logging tag of configure instance
		 * @return this builder for command chaining.
		 */
		public Builder setLoggingTag(String tag) {
			config.loggingTag = tag;
			return this;
		}

		public Builder setConnectionListener(ConnectionListener connectionListener) {
			config.connectionListener = connectionListener;
			return this;
		}

		/**
		 * Sets the session store for {@link InMemoryConnectionStore}.
		 * 
		 * If a custom {@link ResumptionSupportingConnectionStore} is used, the
		 * session store must be provided directly to that implementation. In
		 * that case, the configured session store here will be ignored.
		 * 
		 * @param sessionStore session store, or {@code null}, if not to be
		 *            used.
		 * @return this builder for command chaining.
		 * 
		 * @see DTLSConnector#createConnectionStore
		 * @since 3.0
		 */
		public Builder setSessionStore(SessionStore sessionStore) {
			config.sessionStore = sessionStore;
			return this;
		}

		/**
		 * Sets the resumption verifier.
		 * 
		 * If the client provides a session id in the client hello, this
		 * verifier is used to ensure, that a valid session to resume is
		 * available. An implementation may check a maximum time, or, if the
		 * credentials are expired (e.g. x509 valid range). The default verifier
		 * will just checks, if a DTLS session with that session id is available
		 * in the {@link ResumptionSupportingConnectionStore}.
		 * 
		 * @param resumptionVerifier the resumption verifier
		 * @return this builder for command chaining.
		 * @since 3.0
		 */
		public Builder setResumptionVerifier(ResumptionVerifier resumptionVerifier) {
			config.resumptionVerifier = resumptionVerifier;
			return this;
		}

		/**
		 * Set certificate configuration helper.
		 * 
		 * @param helper custom certificate configuration helper
		 * @return this builder for command chaining.
		 * @since 3.0
		 */
		public Builder setCertificateHelper(CertificateConfigurationHelper helper) {
			config.certificateConfigurationHelper = helper;
			return this;
		}

		/**
		 * Returns a potentially incomplete configuration. Only fields set by
		 * users are affected, there is no default value, no consistency check.
		 * To get a full usable {@link DtlsConnectorConfig} use {@link #build()}
		 * instead.
		 * 
		 * @return the incomplete Configuration
		 */
		public DtlsConnectorConfig getIncompleteConfig() {
			return config;
		}

		/**
		 * Creates an instance of {@code DtlsConnectorConfig} based on the
		 * properties set on this builder.
		 * <p>
		 * If some parameter are not set, the builder tries to derive a
		 * reasonable values from the other parameters.
		 * 
		 * @return the configuration object
		 * @throws IllegalStateException if the configuration is inconsistent
		 */
		public DtlsConnectorConfig build() {
			// set default values
			config.loggingTag = StringUtil.normalizeLoggingTag(config.loggingTag);
			if (config.address == null) {
				config.address = new InetSocketAddress(0);
			}
			if (config.enableReuseAddress == null) {
				config.enableReuseAddress = Boolean.FALSE;
			}
			if (config.useTruncatedCertificatePathForClientsCertificateMessage == null) {
				config.useTruncatedCertificatePathForClientsCertificateMessage = Boolean.TRUE;
			}
			if (config.useTruncatedCertificatePathForValidation == null) {
				config.useTruncatedCertificatePathForValidation = Boolean.TRUE;
			}
			if (config.earlyStopRetransmission == null) {
				config.earlyStopRetransmission = Boolean.TRUE;
			}
			if (config.retransmissionTimeout == null) {
				config.retransmissionTimeout = DEFAULT_RETRANSMISSION_TIMEOUT_MS;
			}
			if (config.additionalTimeoutForEcc == null) {
				config.additionalTimeoutForEcc = DEFAULT_ADDITIONAL_TIMEOUT_FOR_ECC_MS;
			}
			if (config.maxRetransmissions == null) {
				config.maxRetransmissions = DEFAULT_MAX_RETRANSMISSIONS;
			}
			if (config.backOffRetransmission == null) {
				config.backOffRetransmission = config.maxRetransmissions / 2;
			}
			if (config.maxFragmentedHandshakeMessageLength == null) {
				config.maxFragmentedHandshakeMessageLength = DEFAULT_MAX_FRAGMENTED_HANDSHAKE_MESSAGE_LENGTH;
			}
			if (config.clientAuthenticationWanted == null) {
				config.clientAuthenticationWanted = Boolean.FALSE;
			}
			if (config.clientOnly == null) {
				config.clientOnly = Boolean.FALSE;
			}
			if (config.recommendedCipherSuitesOnly == null) {
				config.recommendedCipherSuitesOnly = Boolean.TRUE;
			}
			if (config.recommendedSupportedGroupsOnly == null) {
				config.recommendedSupportedGroupsOnly = Boolean.TRUE;
			}
			if (config.clientAuthenticationRequired == null) {
				if (config.clientOnly) {
					config.clientAuthenticationRequired = Boolean.FALSE;
				} else {
					config.clientAuthenticationRequired = !config.clientAuthenticationWanted;
				}
			}
			if (config.serverOnly == null) {
				config.serverOnly = Boolean.FALSE;
			}
			if (config.defaultHandshakeMode == null) {
				if (config.serverOnly) {
					config.defaultHandshakeMode = DtlsEndpointContext.HANDSHAKE_MODE_NONE;
				} else {
					config.defaultHandshakeMode = DtlsEndpointContext.HANDSHAKE_MODE_AUTO;
				}
			}
			if (config.useServerSessionId == null) {
				config.useServerSessionId = Boolean.TRUE;
			}
			if (config.outboundMessageBufferSize == null) {
				config.outboundMessageBufferSize = 100000;
			}
			if (config.maxDeferredProcessedOutgoingApplicationDataMessages == null) {
				config.maxDeferredProcessedOutgoingApplicationDataMessages = DEFAULT_MAX_DEFERRED_PROCESSED_APPLICATION_DATA_MESSAGES;
			}
			if (config.maxDeferredProcessedIncomingRecordsSize == null) {
				config.maxDeferredProcessedIncomingRecordsSize = DEFAULT_MAX_DEFERRED_PROCESSED_INCOMING_RECORDS_SIZE;
			}
			if (config.maxConnections == null) {
				config.maxConnections = DEFAULT_MAX_CONNECTIONS;
			}
			if (config.connectionThreadCount == null) {
				config.connectionThreadCount = DEFAULT_EXECUTOR_THREAD_POOL_SIZE;
			}
			if (config.receiverThreadCount == null) {
				config.receiverThreadCount = DEFAULT_RECEIVER_THREADS;
			}
			if (config.staleConnectionThreshold == null) {
				config.staleConnectionThreshold = DEFAULT_STALE_CONNECTION_TRESHOLD;
			}
			if (config.maxTransmissionUnitLimit == null) {
				config.maxTransmissionUnitLimit = DEFAULT_MAX_TRANSMISSION_UNIT_LIMIT;
			}
			if (config.sniEnabled == null) {
				config.sniEnabled = Boolean.FALSE;
			}
			if (config.extendedMasterSecretMode == null) {
				config.extendedMasterSecretMode = ExtendedMasterSecretMode.ENABLED;
			}
			if (config.useExtendedWindowFilter == null) {
				config.useExtendedWindowFilter = 0;
			}
			if (config.useAntiReplayFilter == null) {
				config.useAntiReplayFilter = config.useExtendedWindowFilter == 0;
			}
			if (config.useCidUpdateAddressOnNewerRecordFilter == null) {
				config.useCidUpdateAddressOnNewerRecordFilter = Boolean.TRUE;
			}
			if (config.verifyPeersOnResumptionThreshold == null) {
				config.verifyPeersOnResumptionThreshold = DEFAULT_VERIFY_PEERS_ON_RESUMPTION_THRESHOLD_IN_PERCENT;
			}
			if (config.useHelloVerifyRequest == null) {
				config.useHelloVerifyRequest = Boolean.TRUE;
			}
			if (config.useHelloVerifyRequestForPsk == null) {
				config.useHelloVerifyRequestForPsk = config.useHelloVerifyRequest;
			}

			if (config.serverOnly && !config.clientAuthenticationRequired && !config.clientAuthenticationWanted
					&& config.advancedCertificateVerifier != null) {
				throw new IllegalStateException(
						"configured certificate verifier is not used for disabled client authentication!");
			}

			if (config.supportedGroups == null) {
				config.supportedGroups = Collections.emptyList();
			}
			if (config.supportedSignatureAlgorithms == null) {
				config.supportedSignatureAlgorithms = Collections.emptyList();
			}
			if (config.cipherSuiteSelector == null && !config.clientOnly) {
				config.cipherSuiteSelector = new DefaultCipherSuiteSelector();
			}
			if (config.resumptionVerifier == null && config.useServerSessionId && !config.clientOnly) {
				config.resumptionVerifier = new ConnectionStoreResumptionVerifier();
			}

			if (config.supportedCipherSuites == null || config.supportedCipherSuites.isEmpty()) {
				determineCipherSuitesFromConfig();
			}

			// check cipher consistency
			if (config.supportedCipherSuites == null || config.supportedCipherSuites.isEmpty()) {
				throw new IllegalStateException("Supported cipher suites must be set either "
						+ "explicitly or implicitly by means of setting the identity or PSK store");
			}
			for (CipherSuite cipherSuite : config.supportedCipherSuites) {
				if (!cipherSuite.isSupported()) {
					throw new IllegalStateException("cipher-suites " + cipherSuite + " is not supported by JVM!");
				}
			}

			boolean certifacte = false;
			boolean ecc = false;
			boolean psk = false;
			for (CipherSuite suite : config.supportedCipherSuites) {
				if (suite.isPskBased()) {
					verifyPskBasedCipherConfig(suite);
					psk = true;
				} else if (suite.requiresServerCertificateMessage()) {
					verifyCertificateBasedCipherConfig(suite);
					certifacte = true;
				}
				if (suite.isEccBased()) {
					ecc = true;
				}
			}

			if (!psk && config.advancedPskStore != null) {
				throw new IllegalStateException("Advanced PSK store set, but no PSK cipher suite!");
			}

			CertificateProvider provider = config.certificateIdentityProvider;
			NewAdvancedCertificateVerifier verifier = config.advancedCertificateVerifier;

			if (config.certificateConfigurationHelper == null) {
				CertificateConfigurationHelper helper = new CertificateConfigurationHelper();
				if (provider instanceof ConfigurationHelperSetup) {
					((ConfigurationHelperSetup) provider).setupConfigurationHelper(helper);
					config.certificateConfigurationHelper = helper;
				}
				if (verifier instanceof ConfigurationHelperSetup) {
					((ConfigurationHelperSetup) verifier).setupConfigurationHelper(helper);
					config.certificateConfigurationHelper = helper;
				}
			}
			if (ecc) {
				if (config.supportedSignatureAlgorithms.isEmpty()) {
					List<SignatureAndHashAlgorithm> algorithms = new ArrayList<>(SignatureAndHashAlgorithm.DEFAULT);
					if (config.certificateConfigurationHelper != null) {
						ListUtils.addIfAbsent(algorithms,
								config.certificateConfigurationHelper.getDefaultSignatureAndHashAlgorithms());
					}
					config.supportedSignatureAlgorithms = algorithms;
				}
				if (config.supportedGroups.isEmpty()) {
					List<SupportedGroup> defaultGroups = new ArrayList<>(SupportedGroup.getPreferredGroups());
					if (config.certificateConfigurationHelper != null) {
						ListUtils.addIfAbsent(defaultGroups,
								config.certificateConfigurationHelper.getDefaultSupportedGroups());
					}
					config.supportedGroups = defaultGroups;
				}
			} else {
				if (!config.supportedSignatureAlgorithms.isEmpty()) {
					throw new IllegalStateException(
							"supported signature and hash algorithms set, but no ecdhe based cipher suite!");
				}
				if (!config.supportedGroups.isEmpty()) {
					throw new IllegalStateException("supported groups set, but no ecdhe based cipher suite!");
				}
			}

			if (!certifacte) {
				if (provider != null) {
					throw new IllegalStateException("certificate identity set, but no certificate based cipher suite!");
				}
				if (config.advancedCertificateVerifier != null) {
					throw new IllegalStateException("certificate trust set, but no certificate based cipher suite!");
				}
			}

			if (config.recommendedSupportedGroupsOnly) {
				verifyRecommendedSupportedGroupsOnly(config.supportedGroups);
			}

			if (config.certificateConfigurationHelper != null) {
				config.certificateConfigurationHelper
						.verifySignatureAndHashAlgorithmsConfiguration(config.supportedSignatureAlgorithms);
				config.certificateConfigurationHelper.verifySupportedGroupsConfiguration(config.supportedGroups);
				if (provider != null && provider.getSupportedCertificateTypes().contains(CertificateType.X_509)) {
					if (config.clientOnly) {
						if (!config.certificateConfigurationHelper.canBeUsedForAuthentication(true)) {
							throw new IllegalStateException("certificate has no proper key usage for clients!");
						}
					} else if (config.serverOnly) {
						if (!config.certificateConfigurationHelper.canBeUsedForAuthentication(false)) {
							throw new IllegalStateException("certificate has no proper key usage for servers!");
						}
					} else {
						if (!config.certificateConfigurationHelper.canBeUsedForAuthentication(true)) {
							throw new IllegalStateException("certificate has no proper key usage as clients!");
						}
						if (!config.certificateConfigurationHelper.canBeUsedForAuthentication(false)) {
							throw new IllegalStateException("certificate has no proper key usage as servers!");
						}
					}
				}
			}
			if (config.useHelloVerifyRequest && !config.useHelloVerifyRequestForPsk
					&& !CipherSuite.containsPskBasedCipherSuite(config.supportedCipherSuites)) {
				throw new IllegalArgumentException(
						"HELLO_VERIFY_REQUEST disabled for PSK, requires at least one PSK cipher suite!");
			}
			config.supportedCipherSuites = ListUtils.init(config.supportedCipherSuites);
			config.supportedGroups = ListUtils.init(config.supportedGroups);
			config.supportedSignatureAlgorithms = ListUtils.init(config.supportedSignatureAlgorithms);
			return config;
		}

		private void verifyPskBasedCipherConfig(CipherSuite suite) {
			if (config.advancedPskStore == null) {
				throw new IllegalStateException("PSK store must be set for configured " + suite.name());
			}
			if (!config.advancedPskStore.hasEcdhePskSupported() && suite.isEccBased()) {
				throw new IllegalStateException("PSK store doesn't support ECDHE! " + suite.name());
			}
		}

		private void verifyCertificateBasedCipherConfig(CipherSuite suite) {
			if (config.certificateIdentityProvider == null) {
				if (!config.clientOnly) {
					throw new IllegalStateException("Identity must be set for configured " + suite.name());
				}
			} else if (config.certificateConfigurationHelper != null) {
				List<String> keyAlgorithms = config.certificateConfigurationHelper.getSupportedKeyAlgorithms();
				String algorithm = suite.getCertificateKeyAlgorithm().name();
				if (!keyAlgorithms.contains(algorithm)) {
					throw new IllegalStateException(
							"Keys must be " + algorithm + " capable for configured " + suite.name());
				}
			}
			if (config.clientOnly || config.clientAuthenticationRequired || config.clientAuthenticationWanted) {
				if (config.advancedCertificateVerifier == null) {
					throw new IllegalStateException("certificate verifier must be set for configured " + suite.name());
				}
			}
		}

		private void verifyRecommendedCipherSuitesOnly(List<CipherSuite> suites) {
			StringBuilder message = new StringBuilder();
			for (CipherSuite cipherSuite : suites) {
				if (!cipherSuite.isRecommended()) {
					if (message.length() > 0) {
						message.append(", ");
					}
					message.append(cipherSuite.name());
				}
			}
			if (message.length() > 0) {
				throw new IllegalArgumentException("Not recommended cipher suites " + message
						+ " used! (Requires to set recommendedCipherSuitesOnly to false.)");
			}
		}

		private void verifyRecommendedSupportedGroupsOnly(List<SupportedGroup> supportedGroups) {
			StringBuilder message = new StringBuilder();
			for (SupportedGroup group : supportedGroups) {
				if (!group.isRecommended()) {
					if (message.length() > 0) {
						message.append(", ");
					}
					message.append(group.name());
				}
			}
			if (message.length() > 0) {
				throw new IllegalArgumentException("Not recommended supported groups (curves) " + message
						+ " used! (Requires to set recommendedSupportedGroupsOnly to false.)");
			}
		}

		private void verifyRecommendedSignatureAndHashAlgorithmsOnly(List<SignatureAndHashAlgorithm> signatureAndHashAlgorithms) {
			StringBuilder message = new StringBuilder();
			for (SignatureAndHashAlgorithm signature : signatureAndHashAlgorithms) {
				if (!signature.isRecommended()) {
					if (message.length() > 0) {
						message.append(", ");
					}
					message.append(signature.getJcaName());
				}
			}
			if (message.length() > 0) {
				throw new IllegalArgumentException("Not recommended signature and hash algorithms " + message
						+ " used! (Requires to set recommendedSignatureAndHashAlgorithmsOnly to false.)");
			}
		}

		private void determineCipherSuitesFromConfig() {
			// user has not explicitly set cipher suites
			// try to guess his intentions from properties he has set
			List<CipherSuite> ciphers = new ArrayList<>();
			boolean certificates = config.certificateIdentityProvider != null
					|| config.advancedCertificateVerifier != null;
			if (certificates) {
				// currently only ECDSA is supported!
				ciphers.addAll(CipherSuite.getEcdsaCipherSuites(config.recommendedCipherSuitesOnly));
			}

			if (config.advancedPskStore != null) {
				if (config.advancedPskStore.hasEcdhePskSupported()) {
					ciphers.addAll(CipherSuite.getCipherSuitesByKeyExchangeAlgorithm(config.recommendedCipherSuitesOnly,
							KeyExchangeAlgorithm.ECDHE_PSK));
				}
				ciphers.addAll(CipherSuite.getCipherSuitesByKeyExchangeAlgorithm(config.recommendedCipherSuitesOnly,
						KeyExchangeAlgorithm.PSK));
			}
			if (config.preselectedCipherSuites != null) {
				List<CipherSuite> preselect = new ArrayList<>();
				for (CipherSuite cipherSuite : config.preselectedCipherSuites) {
					if (ciphers.contains(cipherSuite)) {
						preselect.add(cipherSuite);
					}
				}
				ciphers = preselect;
			}
			config.supportedCipherSuites = ciphers;
		}
	}
}
