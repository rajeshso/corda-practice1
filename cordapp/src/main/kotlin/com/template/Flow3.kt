package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.loggerFor
import org.slf4j.Logger


@InitiatingFlow
@StartableByRPC
class AcceptResponseFlow(private val responseResult: ResponseResult) : FlowLogic<SignedTransaction>() {

    companion object {
        private val log: Logger = loggerFor<AcceptResponseFlow>()

        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction.")
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
    override fun call(): SignedTransaction {
        val notary = serviceHub
                .networkMapCache
                .notaryIdentities.first()
        val supplier =  serviceHub.myInfo.legalIdentities.first()
        val funder = serviceHub
                .networkMapCache
                .getPeerByLegalName(CordaX500Name("Funder1", "London", "GB")) ?: throw FlowException("No Funder by this name")
        log.info("supplier is " + supplier)
        log.info("funder is " + funder)

        val uuid = getUUID(responseResult.bidLinearId.trim())
        val inputState = getStateAndRefByLinearId(serviceHub, UniqueIdentifier(id=uuid) , BidState::class.java)
        val outputState = inputState.state.data.copy(status = responseResult.result)

        // Stage 1 - Create unsigned transaction
        progressTracker.currentStep = GENERATING_TRANSACTION

        val command = Command(
                value = InvoiceContract.Commands.AcceptBidAction(),
                signers = outputState.participants
                        .map { it.owningKey }
                        .distinct())

        val transactionBuilder = TransactionBuilder(notary)
        transactionBuilder.addInputState(inputState)
        transactionBuilder.addOutputState(outputState, INVOICE_CONTRACT_ID)
        transactionBuilder.addCommand(command)

        // Stage 2 - Verify transaction
        progressTracker.currentStep = VERIFYING_TRANSACTION
        transactionBuilder.verify(serviceHub)

        // Stage 3 - Sign the transaction
        progressTracker.currentStep = SIGNING_TRANSACTION
        val partiallySignedTransaction = serviceHub.signInitialTransaction(transactionBuilder)

        // Stage 4 - Gather counterparty signatures
        progressTracker.currentStep = GATHERING_SIGNATURES
        val requiredSignatureFlowSessions = outputState.participants
                .filter { !serviceHub.myInfo.legalIdentities.contains(it) }
                .distinct()
                .map { initiateFlow(serviceHub.identityService.requireWellKnownPartyFromAnonymous(it)) }

        val fullySignedTransaction = subFlow(CollectSignaturesFlow(
                partiallySignedTransaction,
                requiredSignatureFlowSessions,
                GATHERING_SIGNATURES.childProgressTracker()))

        // Stage 5 - Finalize transaction
        progressTracker.currentStep = FINALISING_TRANSACTION
        return subFlow(FinalityFlow(
                fullySignedTransaction,
                FINALISING_TRANSACTION.childProgressTracker()))
    }

}

@InitiatedBy(AcceptResponseFlow::class)
class FunderAcceptsBidResult(val otherPartyFlow: FlowSession) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(otherPartyFlow) {
            override fun checkTransaction(stx: SignedTransaction) = Unit
            //TODO Add logic
        }

        return subFlow(signTransactionFlow)
    }
}