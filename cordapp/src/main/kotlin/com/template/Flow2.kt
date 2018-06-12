package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker


//Funder Second Flow
@InitiatingFlow
@StartableByRPC
class FundingResponse(private val fundingResponseMessage: FundingResponseMessage) : FlowLogic<SignedTransaction>() {


    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new bid.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contracts constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with my private key.")
        object GATHERING_SIGNATURES : ProgressTracker.Step("Gathering the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGNATURES,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub
                .networkMapCache
                .notaryIdentities.first()
        val funder =  serviceHub.myInfo.legalIdentities.first()
        val supplier = serviceHub
                .networkMapCache
                .getPeerByLegalName(CordaX500Name(fundingResponseMessage.supplier, "London", "GB")) ?: throw FlowException("No Supplier by "+fundingResponseMessage.supplier)

        // Stage 1 - Create unsigned transaction
        progressTracker.currentStep = GENERATING_TRANSACTION
        val outputState = BidState(
                linearId = UniqueIdentifier(),
                id = fundingResponseMessage.id,
                invoiceID = fundingResponseMessage.invoiceID, //TODO: This should be the UUID of the invoice
                price = fundingResponseMessage.price,
                supplier = supplier,
                funder = funder,
                status = "PENDING"
        )
        val command = Command(
                value = InvoiceContract.Commands.BidAction(),
                // signers = outputState.participants.map { it.owningKey } // TODO: Correct this
                signers = listOf(funder.owningKey, supplier.owningKey)
        )

        val transactionBuilder = TransactionBuilder(notary)
                .addOutputState(outputState, INVOICE_CONTRACT_ID)
                .addCommand(command)

        // Stage 2 - Verify transaction
        progressTracker.currentStep = VERIFYING_TRANSACTION
        transactionBuilder.verify(serviceHub)

        // Stage 3 - Sign the transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val selfSignedTx = serviceHub.signInitialTransaction(transactionBuilder)

        // Stage 4 - Gather counterparty signatures
        progressTracker.currentStep = GATHERING_SIGNATURES

        val requiredSignatureFlowSessions = listOf(outputState)
                .flatMap { it.participants }
                .filter { !serviceHub.myInfo.legalIdentities.contains(it) }
                .distinct()
                .map { initiateFlow(serviceHub.identityService.requireWellKnownPartyFromAnonymous(it)) }

        val fullySignedTransaction = subFlow(CollectSignaturesFlow(
                selfSignedTx,
                requiredSignatureFlowSessions,
                GATHERING_SIGNATURES.childProgressTracker()))

        // Stage 5 - Finalize transaction
        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(FinalityFlow(
                transaction = fullySignedTransaction,
                progressTracker = FINALISING_TRANSACTION.childProgressTracker()))

    }
}

//Supplier Receiver second flow
@InitiatedBy(FundingResponse::class)
class NewResponse(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                //TODO: Write some checks
            }
        }
        return subFlow(signTransactionFlow)
    }
}