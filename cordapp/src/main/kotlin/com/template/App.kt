package com.template

import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationWhitelist

// *********
// * Flows *
// *********
//Refer Flow1, Flow2, Flow3 and Flow4

@CordaSerializable
data class InvoiceMessage (val id: Int, val description: String, val potentialFunder: String)

@CordaSerializable
data class FundingResponseMessage (val id: Int, val invoiceID: String, val price: Double, val supplier: String)

@CordaSerializable
data class ResponseResult (val id: Int, val bidLinearId: String, val result: String)

// Serialization whitelist.
class TemplateSerializationWhitelist : SerializationWhitelist {
    override val whitelist: List<Class<*>> = listOf(TemplateData::class.java)
}

// This class is not annotated with @CordaSerializable, so it must be added to the serialization whitelist, above, if
// we want to send it to other nodes within a flow.
data class TemplateData(val payload: String)