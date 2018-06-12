package com.template

import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.vault.QueryCriteria
import org.apache.commons.codec.DecoderException
import org.apache.commons.codec.binary.Hex
import java.nio.ByteBuffer
import java.util.*

fun <T : ContractState> getStateAndRefByLinearId(
        serviceHub: ServiceHub,
        linearId: UniqueIdentifier,
        contractStateType: Class<T>): StateAndRef<T> {

    val criteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))

    return serviceHub
            .vaultService
            .queryBy(contractStateType, criteria)
            .states
            .single()
}

fun getUuid(data1: ByteArray): UUID {
    return UUID(ByteBuffer.wrap(data1, 0, 8).long, ByteBuffer.wrap(data1, 8, 8).long)
}

@Throws(DecoderException::class)
private fun getBytes(s3: String): ByteArray {
    return Hex.decodeHex(s3.toCharArray())
}

fun getUUID(s: String) : UUID {
    val bytes = getBytes(s)
    return getUuid(bytes)
}
