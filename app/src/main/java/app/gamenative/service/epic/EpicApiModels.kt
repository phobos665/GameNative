package app.gamenative.service.epic

/**
 * Epic Games API Response Models
 *
 * Data classes for all Epic API responses based on legendary CLI
 * and Epic's public API documentation.
 *
 * API Base URLs:
 * - OAuth: account-public-service-prod03.ol.epicgames.com
 * - Library: library-service.live.use1a.on.epicgames.com
 * - Catalog: catalog-public-service-prod06.ol.epicgames.com
 * - Launcher: launcher-public-service-prod06.ol.epicgames.com
 * - Entitlements: entitlement-public-service-prod08.ol.epicgames.com
 */

// =============================================================================
// OAuth / Authentication
// =============================================================================

/**
 * OAuth token response
 *
 * Endpoint: POST /account/api/oauth/token
 * Used for: authentication, token refresh
 */
data class EpicOAuthTokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Int,
    val expires_at: String, // ISO 8601 timestamp or epoch ms
    val token_type: String,
    val account_id: String,
    val client_id: String,
    val internal_client: Boolean,
    val client_service: String,
    val displayName: String?,
    val app: String?,
    val in_app_id: String?
)

/**
 * OAuth verify response
 *
 * Endpoint: GET /account/api/oauth/verify
 * Used for: token validation
 */
data class EpicOAuthVerifyResponse(
    val access_token: String,
    val expires_in: Int,
    val expires_at: String,
    val token_type: String,
    val refresh_token: String,
    val refresh_expires: Int,
    val refresh_expires_at: String,
    val account_id: String,
    val client_id: String,
    val internal_client: Boolean,
    val client_service: String,
    val displayName: String,
    val app: String,
    val in_app_id: String
)

/**
 * OAuth exchange code response
 *
 * Endpoint: GET /account/api/oauth/exchange
 * Used for: generating exchange codes for game launches
 */
data class EpicExchangeCodeResponse(
    val code: String,
    val creatingClientId: String,
    val expiresInSeconds: Int
)

// =============================================================================
// Library Service
// =============================================================================

/**
 * Library items list response
 *
 * Endpoint: GET /library/api/public/items
 * Used for: fetching user's game library
 */
data class EpicLibraryResponse(
    val responseMetadata: EpicResponseMetadata,
    val records: List<EpicLibraryItem>
)

data class EpicResponseMetadata(
    val nextCursor: String?
)

/**
 * Individual library item
 */
data class EpicLibraryItem(
    val appName: String,
    val catalogItemId: String,
    val namespace: String,
    val sandboxType: String?,
    val productId: String?
)

// =============================================================================
// Catalog Service
// =============================================================================

/**
 * Catalog item (game info) response
 *
 * Endpoint: GET /catalog/api/shared/namespace/{namespace}/bulk/items
 * Used for: fetching detailed game information
 */
data class EpicCatalogItemResponse(
    val id: String,
    val title: String,
    val description: String,
    val longDescription: String?,
    val technicalDetails: String?,
    val keyImages: List<EpicKeyImage>?,
    val categories: List<EpicCategory>?,
    val namespace: String,
    val status: String,
    val creationDate: String?,
    val lastModifiedDate: String?,
    val customAttributes: Map<String, EpicCustomAttribute>?,
    val entitlementName: String?,
    val entitlementType: String?,
    val itemType: String?,
    val releaseInfo: List<EpicReleaseInfo>?,
    val developer: String?,
    val developerDisplayName: String?,
    val developerId: String?,
    val eulaIds: List<String>?,
    val endOfSupport: Boolean?,
    val dlcItemList: List<String>?,
    val ageGatings: Map<String, Int>?,
    val applicationId: String?,
    val baseAppName: String?,
    val baseProductId: String?,
    val mainGameItem: EpicMainGameItem?
)

data class EpicKeyImage(
    val type: String,
    val url: String,
    val md5: String?,
    val width: Int?,
    val height: Int?
)

data class EpicCategory(
    val path: String
)

data class EpicCustomAttribute(
    val type: String,
    val value: String
)

data class EpicReleaseInfo(
    val id: String?,
    val appId: String?,
    val platform: List<String>?,
    val dateAdded: String?,
    val releaseNote: String?,
    val versionTitle: String?
)

data class EpicMainGameItem(
    val id: String,
    val namespace: String
)

// =============================================================================
// Launcher / Assets Service
// =============================================================================

/**
 * Game assets response (manifest list)
 *
 * Endpoint: GET /launcher/api/public/assets/{platform}
 * Used for: getting available game builds/versions
 */
data class EpicAssetsResponse(
    val elements: List<EpicAssetElement>
)

data class EpicAssetElement(
    val appName: String,
    val labelName: String,
    val buildVersion: String,
    val catalogItemId: String,
    val namespace: String,
    val assetId: String,
    val metadata: Map<String, String>?
)

/**
 * Game manifest response
 *
 * Endpoint: GET /launcher/api/public/assets/v2/platform/{platform}/namespace/{namespace}/catalogItem/{catalogItemId}/app/{appName}/label/{label}
 * Used for: downloading game manifest with chunk information
 */
data class EpicManifestResponse(
    val elements: List<EpicManifestElement>
)

data class EpicManifestElement(
    val appName: String,
    val labelName: String,
    val buildVersion: String,
    val hash: String,
    val manifestLocation: EpicManifestLocation,
    val catalogItemId: String,
    val namespace: String,
    val assetId: String,
    val metadata: Map<String, String>?
)

data class EpicManifestLocation(
    val uri: String
)

// =============================================================================
// Entitlements Service
// =============================================================================

/**
 * User entitlements response
 *
 * Endpoint: GET /entitlement/api/account/{accountId}/entitlements
 * Used for: checking what games/DLC user owns
 */
data class EpicEntitlementsResponse(
    val entitlements: List<EpicEntitlement>,
    val paging: EpicPaging?
)

data class EpicEntitlement(
    val id: String,
    val entitlementName: String,
    val namespace: String,
    val catalogItemId: String,
    val accountId: String,
    val identityId: String?,
    val entitlementType: String,
    val grantDate: String,
    val consumable: Boolean?,
    val status: String,
    val active: Boolean?,
    val useCount: Int?,
    val entitlementSource: String?,
    val itemId: String?,
    val grantedCode: String?,
    val platformType: String?,
    val country: String?
)

data class EpicPaging(
    val start: Int,
    val count: Int,
    val total: Int
)

// =============================================================================
// Ownership Service
// =============================================================================

/**
 * Ownership token response
 *
 * Endpoint: POST /ecommerceintegration/api/public/platforms/EPIC/identities/{accountId}/ownershipToken
 * Used for: DRM verification during game launch
 */
data class EpicOwnershipTokenResponse(
    val token: String // Binary token, base64 encoded
)

// =============================================================================
// Account Service
// =============================================================================

/**
 * External auths response
 *
 * Endpoint: GET /account/api/public/account/{accountId}/externalAuths
 * Used for: getting linked accounts (PSN, Xbox, etc.)
 */
data class EpicExternalAuthsResponse(
    val externalAuths: List<EpicExternalAuth>
)

data class EpicExternalAuth(
    val accountId: String,
    val type: String,
    val externalAuthId: String,
    val externalAuthIdType: String?,
    val externalDisplayName: String?,
    val authIds: List<EpicAuthId>?,
    val dateAdded: String?
)

data class EpicAuthId(
    val id: String,
    val type: String
)

// =============================================================================
// Error Responses
// =============================================================================

/**
 * Standard Epic API error response
 */
data class EpicErrorResponse(
    val errorCode: String,
    val errorMessage: String,
    val messageVars: List<String>?,
    val numericErrorCode: Int?,
    val originatingService: String?,
    val intent: String?,
    val error_description: String?,
    val error: String?
)

// =============================================================================
// Cloud Save (Optional - for future implementation)
// =============================================================================

/**
 * Cloud save list response
 *
 * Endpoint: GET /datastorage/api/v1/access/egstore/savegames/{accountId}/{namespace}
 * Used for: syncing cloud saves
 */
data class EpicCloudSaveResponse(
    val files: List<EpicCloudSaveFile>
)

data class EpicCloudSaveFile(
    val uniqueFilename: String,
    val filename: String,
    val hash: String,
    val length: Int,
    val uploaded: String,
    val storageType: String
)
