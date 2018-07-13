
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.security.PrivateKey;
import java.security.Security;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.hyperledger.fabric.protos.peer.Query.ChaincodeInfo;
import org.hyperledger.fabric.sdk.BlockEvent;
import org.hyperledger.fabric.sdk.BlockchainInfo;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.ChaincodeResponse.Status;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.Enrollment;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.Orderer;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.User;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.exception.ProposalException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;
import org.hyperledger.fabric_ca.sdk.exception.EnrollmentException;

public class BYFNTest {

	static {
		Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
	}
	
	public static void main(String[] args) throws EnrollmentException, org.hyperledger.fabric_ca.sdk.exception.InvalidArgumentException, MalformedURLException {
		try {
			HFClient hfclient = HFClient.createNewInstance();
			CryptoSuite cryptoSuite = CryptoSuite.Factory.getCryptoSuite();
			hfclient.setCryptoSuite(cryptoSuite);
			//设置用户,name任意
			User user = getFabricUser4Local("user","org1","Org1MSP");
			hfclient.setUserContext(user);
			
			//创建通道的客户端代理
			Channel channel = hfclient.newChannel("mychannel");
			//
			Orderer orderer = hfclient.newOrderer("orderer", "grpc://9.12.248.90:7050");
			channel.addOrderer(orderer);
			
			Peer peer = hfclient.newPeer("peer0", "grpc://9.12.248.90:7051");
			channel.addPeer(peer);//添加背书节点
			try {
				channel.initialize();
			}catch (Exception e) {
				e.printStackTrace();
				System.exit(0);
			}
			
			//获取当前peer加入的某个channel的区块数
			BlockchainInfo blockchainInfo = channel.queryBlockchainInfo(peer);
			System.out.println("the current ledger blocks height:"+blockchainInfo.getHeight() + " ");
			
			//获取当前peer加入了哪些channel
			System.out.println("获取当前peer加入了哪些channel:");
			Set<String> peerChannels = hfclient.queryChannels(peer);
			peerChannels.forEach(System.out::println);
			
			//获取当前peer服务器中状态为Install的chaincode的信息
			System.out.println("获取当前peer服务器中状态为Install的chaincode的信息:");
			List<ChaincodeInfo> installccs = hfclient.queryInstalledChaincodes(peer);
			installccs.forEach(cc -> System.out.println(cc.getPath()));
			//根据当前peer加入的某个channel中状态为instantiate的chaincode的详细信息
			List<ChaincodeInfo> instantiatedChaincodes = channel.queryInstantiatedChaincodes(peer);
			instantiatedChaincodes.forEach(cc -> System.out.println(cc.getPath()));
			
			
			//调用chaincode的query方法
			ChaincodeID ccId = ChaincodeID.newBuilder().setName("mycc").build();
			QueryByChaincodeRequest queryByChaincodeRequest = hfclient.newQueryProposalRequest();
			queryByChaincodeRequest.setArgs(new String[] {"a"});
			queryByChaincodeRequest.setFcn("query");
			queryByChaincodeRequest.setChaincodeID(ccId);
			Collection<ProposalResponse> queryProposals = channel.queryByChaincode(queryByChaincodeRequest);
			for(ProposalResponse pr : queryProposals) {
				if(!pr.isVerified() || pr.getStatus() != Status.SUCCESS) {
					System.out.println("Failed query proposal from peer!");
				}else {
					String payload = pr.getProposalResponse().getResponse().getPayload().toStringUtf8();
					System.out.println("Query a success : "+ payload);
				}
			}
			
			queryByChaincodeRequest = hfclient.newQueryProposalRequest();
			queryByChaincodeRequest.setArgs(new String[] {"b"});
			queryByChaincodeRequest.setFcn("query");
			queryByChaincodeRequest.setChaincodeID(ccId);
			queryProposals = channel.queryByChaincode(queryByChaincodeRequest);
			for(ProposalResponse pr : queryProposals) {
				if(!pr.isVerified() || pr.getStatus() != Status.SUCCESS) {
					System.out.println("Failed query proposal from peer!");
				}else {
					String payload = pr.getProposalResponse().getResponse().getPayload().toStringUtf8();
					System.out.println("Query b success : "+ payload);
				}
			}
			
			sendTranstion(hfclient, channel, ccId, user);
			
		} catch (IllegalAccessException | InstantiationException | ClassNotFoundException | CryptoException
				| InvalidArgumentException | NoSuchMethodException | InvocationTargetException e) {
			e.printStackTrace();
		} catch (ProposalException e) {
			e.printStackTrace();
		}
	}
	//调用已经部署的chaincode的invoke方法
	private static CompletableFuture<BlockEvent.TransactionEvent> sendTranstion(HFClient client, Channel channel, ChaincodeID ccID, User user) throws ProposalException, InvalidArgumentException{
		Collection<ProposalResponse> successful = new LinkedList<>();
		TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
		transactionProposalRequest.setChaincodeID(ccID);
		transactionProposalRequest.setFcn("invoke");
		transactionProposalRequest.setArgs(new String[] {"a","b","1"});
		transactionProposalRequest.setProposalWaitTime(300000);
		transactionProposalRequest.setUserContext(user);
		Collection<ProposalResponse> invokePropResp = channel.sendTransactionProposal(transactionProposalRequest);
		for(ProposalResponse pr : invokePropResp) {
			if(pr.getStatus() == Status.SUCCESS) {
				System.out.printf("successful transaction proposal response Txid : %s from peer: %s", pr.getTransactionID(), pr.getPeer().getName());
				successful.add(pr);
			}else {
				System.out.printf("fail %s", "ddd");
			}
		}
		System.out.println();
		return channel.sendTransaction(successful,user);
	}

	/**
	 * 根据cryptogen模块生成的账号创建fabric账号
	 * @param username
	 * @param org
	 * @param orgId
	 * @return
	 */
	private static User getFabricUser4Local(String username, String org, String orgId) {
		FabricUserImpl user = new FabricUserImpl(username, org);
		user.setMspId(orgId);
		
		try {
			String certificate = new String(IOUtils.toByteArray(new FileInputStream("E:\\fabric_certs\\byfn_admin.pem")));
			File privateKeyFile = new File("E:\\fabric_certs\\byfn_sk");
			PrivateKey pk = getPrivateKeyFromBytes(IOUtils.toByteArray(new FileInputStream(privateKeyFile)));
			
			EnrollmentImpl enrollement = new EnrollmentImpl(pk, certificate);
			user.setEnrollment(enrollement);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return user;
	}
	
	private static PrivateKey getPrivateKeyFromBytes(byte[] data) throws IOException {
		final Reader pemReader = new StringReader(new String(data));
		final PrivateKeyInfo pemPair;
		PEMParser pemParse = new PEMParser(pemReader);
		pemPair = (PrivateKeyInfo) pemParse.readObject();
		PrivateKey pk = new JcaPEMKeyConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getPrivateKey(pemPair);
		pemParse.close();
		return pk;
	}

	static final class EnrollmentImpl implements Enrollment, Serializable{
		
		private static final long serialVersionUID = 1L;
		private final PrivateKey privateKey;
		private final String certificate;
		
		public EnrollmentImpl(PrivateKey pk, String c) {
			this.privateKey = pk;
			this.certificate = c;
		}

		@Override
		public PrivateKey getKey() {
			return privateKey;
		}

		@Override
		public String getCert() {
			return certificate;
		}
		
	}
	
	static final class FabricUserImpl implements User,Serializable{
		
		private static final long serialVersionUID = 1L;
		private String name;
		private Set<String> roles;
		private String account;
		private String affiliation;
		private String organization;
		private String enrollmentSecret;
		Enrollment enrollment = null;
		public FabricUserImpl(String name, String org) {
			this.name = name;
			this.organization = org;
		}
		@Override
		public String getName() {
			return this.name;
		}

		@Override
		public Set<String> getRoles() {
			return this.roles;
		}
		
		public void setRoles(Set<String> roles) {
			this.roles = roles;
		}

		@Override
		public String getAccount() {
			return this.account;
		}
		
		public void setAccount(String account) {
			this.account = account;
		}

		@Override
		public String getAffiliation() {
			return this.affiliation;
		}
		public void setAffiliation(String af) {
			this.affiliation = af;
		}

		@Override
		public Enrollment getEnrollment() {
			return this.enrollment;
		}
		
		public boolean isEnrolled() {
			return this.enrollment != null;
		}
		
		public String getEnrollmentSecret() {
			return this.enrollmentSecret;
		}
		
		public void setEnrollmentSecret(String es) {
			this.enrollmentSecret = es;
		}
		
		public void setEnrollment(Enrollment e) {
			this.enrollment = e;
		}
		String mspId;
		@Override
		public String getMspId() {
			return this.mspId;
		}
		public void setMspId(String mspId) {
			this.mspId = mspId;
		}
		
	}
}
