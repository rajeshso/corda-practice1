package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.StatesToRecord
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

//Supplier first flow
@InitiatingFlow
@StartableByRPC
class IssueInvoice(private val invoice: InvoiceMessage) : FlowLogic<SignedTransaction>() {
    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on new invoice.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contracts constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
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
    override fun call() : SignedTransaction {
        val notary = serviceHub
                .networkMapCache
                .notaryIdentities.first()
        val supplier =  serviceHub.myInfo.legalIdentities.first()

        //Step 1 - Create Unsigned Transaction
        progressTracker.currentStep = GENERATING_TRANSACTION
        val outputState = InvoiceState(UniqueIdentifier(), supplier,null, invoice.description )

        val command = Command(
                value=InvoiceContract.Commands.Issue(),
                signers= listOf(ourIdentity.owningKey))


        val transactionBuilder = TransactionBuilder(notary)
                .addOutputState(outputState, INVOICE_CONTRACT_ID)
                .addCommand(command)

        //Step 2 - Verify Transaction
        progressTracker.currentStep = VERIFYING_TRANSACTION
        transactionBuilder.verify(serviceHub)

        //Step 3 - Sign the Transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val selfSignedTransaction = serviceHub.signInitialTransaction(transactionBuilder)


        // Stage 4 - Finalize transaction
        progressTracker.currentStep = FINALISING_TRANSACTION
        val ftx = subFlow(FinalityFlow(selfSignedTransaction))

        subFlow(BroadcastInvoice(ftx, invoice.potentialFunder))
        return ftx
    }

}

@InitiatingFlow
class BroadcastInvoice(val stx: SignedTransaction, val potentialFunder: String) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() : Unit {
        val funder = serviceHub
                .networkMapCache
                .getPeerByLegalName(CordaX500Name(potentialFunder, "London", "GB")) ?: throw FlowException("No Funder by this name")
        val session = initiateFlow(funder)
        subFlow(SendTransactionFlow(session, stx ))
    }
}

//Funder Receiver first flow
@InitiatedBy(BroadcastInvoice::class)
class RecordInvoices(val counterpartySession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val flow = ReceiveTransactionFlow(
                otherSideSession = counterpartySession,
                checkSufficientSignatures = true,
                statesToRecord = StatesToRecord.ALL_VISIBLE
        )
        subFlow(flow)
    }
}