package com.oakinvest.b2g.batch.bitcoin.step4.relations;

import com.oakinvest.b2g.domain.bitcoin.BitcoinAddress;
import com.oakinvest.b2g.domain.bitcoin.BitcoinBlock;
import com.oakinvest.b2g.domain.bitcoin.BitcoinBlockState;
import com.oakinvest.b2g.domain.bitcoin.BitcoinTransaction;
import com.oakinvest.b2g.domain.bitcoin.BitcoinTransactionOutput;
import com.oakinvest.b2g.dto.ext.bitcoin.bitcoind.BitcoindBlockData;
import com.oakinvest.b2g.repository.bitcoin.BitcoinAddressRepository;
import com.oakinvest.b2g.repository.bitcoin.BitcoinBlockRepository;
import com.oakinvest.b2g.repository.bitcoin.BitcoinTransactionRepository;
import com.oakinvest.b2g.service.StatusService;
import com.oakinvest.b2g.service.ext.bitcoin.bitcoind.BitcoindService;
import com.oakinvest.b2g.util.bitcoin.batch.BitcoinBatchTemplate;
import org.neo4j.ogm.session.Session;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Bitcoin import relations batch.
 * Created by straumat on 27/02/17.
 */
@Component
public class BitcoinBatchRelations extends BitcoinBatchTemplate {

	/**
	 * Log prefix.
	 */
	private static final String PREFIX = "Relations batch";

	/**
	 * Constructor.
	 *
	 * @param newBlockRepository       blockRepository
	 * @param newAddressRepository     addressRepository
	 * @param newTransactionRepository transactionRepository
	 * @param newBitcoindService       bitcoindService
	 * @param newStatus                status
	 * @param newSession               session
	 */
	public BitcoinBatchRelations(final BitcoinBlockRepository newBlockRepository, final BitcoinAddressRepository newAddressRepository, final BitcoinTransactionRepository newTransactionRepository, final BitcoindService newBitcoindService, final StatusService newStatus, final Session newSession) {
		super(newBlockRepository, newAddressRepository, newTransactionRepository, newBitcoindService, newStatus, newSession);
	}

	/**
	 * Returns the log prefix to display in each log.
	 */
	@Override
	public final String getLogPrefix() {
		return PREFIX;
	}

	/**
	 * Return the block to process.
	 *
	 * @return block to process.
	 */
	@Override
	protected final Long getBlockHeightToProcess() {
		BitcoinBlock blockToTreat = getBlockRepository().findFirstBlockByState(BitcoinBlockState.TRANSACTIONS_IMPORTED);
		if (blockToTreat != null) {
			return blockToTreat.getHeight();
		} else {
			return null;
		}
	}

	/**
	 * Process block.
	 *
	 * @param blockHeight block height to process.
	 */
	@Override
	protected final BitcoinBlock processBlock(final long blockHeight) {
		final BitcoinBlock blockToTreat = getBlockRepository().findByHeight(blockHeight);
		// -----------------------------------------------------------------------------------------------------
		// Setting the relationship between blocks and transactions.
		blockToTreat.getTx().stream()
				.filter(t -> !t.equals(GENESIS_BLOCK_TRANSACTION))
				.forEach(t -> {
					BitcoinTransaction bt = getTransactionRepository().findByTxId(t);
					bt.setBlock(blockToTreat);
					blockToTreat.getTransactions().add(bt);
				});
		getBlockRepository().save(blockToTreat);
		getSession().clear();

		// -----------------------------------------------------------------------------------------------------
		// We set the previous and the next block.
		BitcoinBlock previousBlock = getBlockRepository().findByHash(blockToTreat.getPreviousBlockHash());
		blockToTreat.setPreviousBlock(previousBlock);
		if (previousBlock != null) {
			previousBlock.setNextBlock(blockToTreat);
			getBlockRepository().save(previousBlock);
		}
		getBlockRepository().save(blockToTreat);
		getSession().clear();

		// -----------------------------------------------------------------------------------------------------
		// we link the addresses to the input and the origin transaction.
		blockToTreat.getTransactions()
				.forEach(
						t -> {
							addLog("- Transaction " + t.getTxId());
							// For each Vin.
							t.getInputs()
									.stream()
									// If the txid set in the VIN is null, it's a coinbase transaction.
									.filter(vin -> !vin.isCoinbase())
									.forEach(vin -> {
										// We retrieve the original transaction.
										BitcoinTransaction originTransaction = getTransactionRepository().findByTxId(vin.getTxId());
										if (originTransaction != null) {
											// We retrieve the original transaction output.
											Optional<BitcoinTransactionOutput> originTransactionOutput = originTransaction.getOutputByIndex(vin.getvOut());
											if (originTransactionOutput.isPresent()) {
												// We set the addresses "from" if it's not a coinbase transaction.
												vin.setTransactionOutput(originTransactionOutput.get());

												// We set all the addresses linked to this input
												originTransactionOutput.get().getAddresses()
														.stream()
														.filter(Objects::nonNull)
														.forEach(a -> {
															BitcoinAddress address = getAddressRepository().findByAddress(a);
															address.getInputTransactions().add(vin);
															getAddressRepository().save(address);
														});
												addLog("-- Done processing vin : " + vin);
											} else {
												// We try to recreate the transaction and its vins & vouts.
												getTransactionRepository().findByTxId(originTransaction.getTxId());
												BitcoinBlock originBlock = originTransaction.getBlock();
												getTransactionRepository().delete(originTransaction.getId());

												BitcoindBlockData blockData = getBitcoindService().getBlockData(originBlock.getHeight());
												BitcoinTransaction transaction = getMapper().rawTransactionResultToBitcoinTransaction(blockData.getRawTransactionResult(originTransaction.getTxId()).get());
												transaction.setBlock(originBlock);
												getTransactionRepository().save(transaction);

												addError("Impossible to find the original output transaction " + vin.getTxId() + " / " + vin.getvOut());
												throw new RuntimeException("Impossible to find the original output transaction " + vin.getTxId() + " / " + vin.getvOut());
											}
										} else {
											addError("Impossible to find the original transaction " + vin.getTxId());
											throw new RuntimeException("Impossible to find the original transaction " + vin.getTxId());
										}
									});

							// For each Vout.
							t.getOutputs()
									.forEach(vout -> {
										vout.getAddresses().stream()
												.filter(Objects::nonNull)
												.forEach(a -> {
													BitcoinAddress address = getAddressRepository().findByAddress(a);
													address.getOutputTransactions().add(vout);
													getAddressRepository().save(address);
												});
										addLog("-- Done processing vout : " + vout);
									});
							addLog("-- Transaction " + t.getTxId() + " relations processed");
						}
				);
		getBlockRepository().save(blockToTreat);
		getSession().clear();
		return blockToTreat;
	}

	/**
	 * Return the state to set to the block that has been processed.
	 *
	 * @return state to set of the block that has been processed.
	 */
	@Override
	protected final BitcoinBlockState getNewStateOfProcessedBlock() {
		return BitcoinBlockState.IMPORTED;
	}

}
