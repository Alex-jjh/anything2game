package com.anything2game.app.data.api

import com.anything2game.contract.ContractJson
import com.anything2game.contract.game.*
import com.anything2game.contract.space.*
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

class ActivityApi(private val transport: ApiTransport) {
    private val json = ContractJson.json
    private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun path(id: String) = "/v1/spaces/${segment(id)}"
    suspend fun spaces(): List<Space> = json.decodeFromString(transport.request("GET", "/v1/spaces"))
    suspend fun space(id: String): Space = json.decodeFromString(transport.request("GET", path(id)))
    suspend fun create(request: CreateSpaceRequest): Space =
        json.decodeFromString(transport.request("POST", "/v1/spaces", json.encodeToString(request)))
    suspend fun rename(id: String, request: RenameSpaceRequest): Space =
        json.decodeFromString(transport.request("PATCH", path(id), json.encodeToString(request)))
    suspend fun member(id: String, request: SpaceMemberRequest, add: Boolean): Space =
        json.decodeFromString(transport.request(if (add) "PUT" else "DELETE", path(id) + "/members", json.encodeToString(request)))

    suspend fun submit(request: GenerationRequest): GenerationStatus =
        json.decodeFromString(transport.request("POST", "/v1/generation-jobs", json.encodeToString(request)))
    suspend fun status(id: String): GenerationStatus =
        json.decodeFromString(transport.request("GET", "/v1/generation-jobs/${segment(id)}"))
    suspend fun cancel(id: String): GenerationStatus =
        json.decodeFromString(transport.request("POST", "/v1/generation-jobs/${segment(id)}/cancel"))
    suspend fun archive(version: ContentVersion) = transport.archive("/v1/content/${segment(version.contentId)}/versions/${segment(version.contentVersion)}")
}
