package com.wkq.dev.metadata.model

enum class MetadataStandard(
    val displayName: String,
    val groupName: String,
    val accentColor: String
) {
    IIM_APPLICATION("IPTC IIM", "IIM Application Record", "#F59E0B"),
    IPTC_CORE("IPTC Core", "XMP Iptc4xmpCore", "#22C55E"),
    IPTC_EXTENSION("IPTC Extension", "XMP Iptc4xmpExt", "#65A30D"),
    DUBLIN_CORE("Dublin Core", "XMP Dublin Core", "#DC2626"),
    PHOTOSHOP("Photoshop", "XMP Photoshop", "#7C3AED")
}

enum class MetadataFieldValueType {
    TEXT,
    TEXT_LIST,
    LANG_ALT,
    URI,
    BOOLEAN,
    STRUCT,
    STRUCT_LIST
}

enum class MetadataFieldGroup(
    val displayName: String
) {
    BASIC_CONTENT("Basic Content"),
    CREATOR_CONTACT("Creator & Contact"),
    LOCATION("Location"),
    PEOPLE_ORGANISATION("People & Organisation"),
    RIGHTS_LICENSING("Rights & Licensing"),
    ADMIN_WORKFLOW("Admin & Workflow"),
    AI_DISCLOSURE("AI & Synthetic Content"),
    STRUCTURED_ADVANCED("Structured Advanced"),
    LEGACY_IIM("Legacy IIM")
}

data class MetadataFieldCapability(
    val key: String,
    val group: MetadataFieldGroup,
    val writeSupported: Boolean,
    val presetSupported: Boolean,
    val perPhotoSupported: Boolean,
    val importSupported: Boolean,
    val valueType: MetadataFieldValueType
)

data class MetadataFieldDefinition(
    val key: String,
    val label: String,
    val standard: MetadataStandard,
    val detail: String,
    val defaultValue: String = "",
    val multiline: Boolean = false,
    val autofill: Boolean = false,
    val valueType: MetadataFieldValueType = MetadataFieldValueType.TEXT
)

object MetadataFieldDefinitions {
    const val IIM_RECORD_VERSION = "iim.recordVersion"
    const val IIM_OBJECT_TYPE = "iim.objectType"
    const val IIM_OBJECT_ATTRIBUTE = "iim.objectAttribute"
    const val IIM_OBJECT_NAME = "iim.objectName"
    const val IIM_EDIT_STATUS = "iim.editStatus"
    const val IIM_URGENCY = "iim.urgency"
    const val IIM_CATEGORY = "iim.category"
    const val IIM_SUPPLEMENTAL_CATEGORY = "iim.supplementalCategory"
    const val IIM_KEYWORDS = "iim.keywords"
    const val IIM_SPECIAL_INSTRUCTIONS = "iim.specialInstructions"
    const val IIM_DATE_CREATED = "iim.dateCreated"
    const val IIM_TIME_CREATED = "iim.timeCreated"
    const val IIM_JOB_IDENTIFIER = "iim.jobIdentifier"
    const val IIM_BYLINE = "iim.byline"
    const val IIM_BYLINE_TITLE = "iim.bylineTitle"
    const val IIM_CITY = "iim.city"
    const val IIM_SUBLOCATION = "iim.sublocation"
    const val IIM_STATE = "iim.state"
    const val IIM_COUNTRY_CODE = "iim.countryCode"
    const val IIM_COUNTRY_NAME = "iim.countryName"
    const val IIM_HEADLINE = "iim.headline"
    const val IIM_CREDIT = "iim.credit"
    const val IIM_SOURCE = "iim.source"
    const val IIM_COPYRIGHT = "iim.copyright"
    const val IIM_CONTACT = "iim.contact"
    const val IIM_CAPTION = "iim.caption"
    const val IIM_WRITER = "iim.writer"

    const val IPTC_CORE_CREATOR_CITY = "iptcCore.creatorCity"
    const val IPTC_CORE_CREATOR_REGION = "iptcCore.creatorRegion"
    const val IPTC_CORE_CREATOR_COUNTRY = "iptcCore.creatorCountry"
    const val IPTC_CORE_CREATOR_COUNTRY_CODE = "iptcCore.creatorCountryCode"
    const val IPTC_CORE_CREATOR_ADDRESS = "iptcCore.creatorAddress"
    const val IPTC_CORE_CREATOR_EMAIL = "iptcCore.creatorEmail"
    const val IPTC_CORE_CREATOR_PHONE = "iptcCore.creatorPhone"
    const val IPTC_CORE_CREATOR_WEB_URL = "iptcCore.creatorWebUrl"
    const val IPTC_CORE_LOCATION = "iptcCore.location"
    const val IPTC_CORE_COUNTRY_CODE = "iptcCore.countryCode"
    const val IPTC_CORE_INTELLECTUAL_GENRE = "iptcCore.intellectualGenre"
    const val IPTC_CORE_SCENE = "iptcCore.scene"
    const val IPTC_CORE_SUBJECT_CODE = "iptcCore.subjectCode"

    const val IPTC_EXT_LOCATION_CREATED_COUNTRY_CODE = "iptcExt.locationCreatedCountryCode"
    const val IPTC_EXT_LOCATION_CREATED_COUNTRY_NAME = "iptcExt.locationCreatedCountryName"
    const val IPTC_EXT_LOCATION_CREATED_REGION = "iptcExt.locationCreatedRegion"
    const val IPTC_EXT_LOCATION_CREATED_CITY = "iptcExt.locationCreatedCity"
    const val IPTC_EXT_LOCATION_CREATED_SUBLOCATION = "iptcExt.locationCreatedSublocation"
    const val IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE = "iptcExt.locationShownCountryCode"
    const val IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME = "iptcExt.locationShownCountryName"
    const val IPTC_EXT_LOCATION_SHOWN_REGION = "iptcExt.locationShownRegion"
    const val IPTC_EXT_LOCATION_SHOWN_CITY = "iptcExt.locationShownCity"
    const val IPTC_EXT_LOCATION_SHOWN_SUBLOCATION = "iptcExt.locationShownSublocation"
    const val IPTC_EXT_EVENT = "iptcExt.event"
    const val IPTC_EXT_DIGITAL_SOURCE_TYPE = "iptcExt.digitalSourceType"
    const val IPTC_EXT_PERSON_IN_IMAGE = "iptcExt.personInImage"
    const val IPTC_EXT_ORGANISATION_IN_IMAGE_NAME = "iptcExt.organisationInImageName"
    const val IPTC_EXT_ORGANISATION_IN_IMAGE_CODE = "iptcExt.organisationInImageCode"
    const val IPTC_EXT_ARTWORK_TITLE = "iptcExt.artworkTitle"
    const val IPTC_EXT_ARTWORK_CREATOR = "iptcExt.artworkCreator"
    const val IPTC_EXT_ARTWORK_SOURCE = "iptcExt.artworkSource"
    const val IPTC_EXT_IMAGE_CREATOR_NAME = "iptcExt.imageCreatorName"
    const val IPTC_EXT_IMAGE_SUPPLIER_NAME = "iptcExt.imageSupplierName"
    const val IPTC_EXT_IMAGE_SUPPLIER_ID = "iptcExt.imageSupplierId"
    const val IPTC_EXT_REGISTRY_ENTRY_ROLE = "iptcExt.registryEntryRole"
    const val IPTC_EXT_REGISTRY_ITEM_ID = "iptcExt.registryItemId"
    const val IPTC_EXT_REGISTRY_ORGANISATION_ID = "iptcExt.registryOrganisationId"
    const val IPTC_EXT_MODEL_RELEASE_STATUS = "iptcExt.modelReleaseStatus"
    const val IPTC_EXT_MODEL_RELEASE_ID = "iptcExt.modelReleaseId"
    const val IPTC_EXT_PROPERTY_RELEASE_STATUS = "iptcExt.propertyReleaseStatus"
    const val IPTC_EXT_PROPERTY_RELEASE_ID = "iptcExt.propertyReleaseId"
    const val IPTC_EXT_DATA_MINING = "iptcExt.dataMining"
    const val IPTC_EXT_AI_SYSTEM_USED = "iptcExt.aiSystemUsed"
    const val IPTC_EXT_AI_SYSTEM_VERSION_USED = "iptcExt.aiSystemVersionUsed"
    const val IPTC_EXT_AI_PROMPT = "iptcExt.aiPrompt"
    const val IPTC_EXT_AI_PROMPT_WRITER = "iptcExt.aiPromptWriter"
    const val IPTC_EXT_IMAGE_REGION = "iptcExt.imageRegion"

    const val PLUS_LICENSOR_NAME = "plus.licensorName"
    const val PLUS_LICENSOR_EMAIL = "plus.licensorEmail"
    const val PLUS_LICENSOR_URL = "plus.licensorUrl"
    const val PLUS_LICENSOR_PHONE = "plus.licensorPhone"
    const val PLUS_COPYRIGHT_OWNER_NAME = "plus.copyrightOwnerName"

    const val DC_TITLE = "dc.title"
    const val DC_CREATOR = "dc.creator"
    const val DC_DESCRIPTION = "dc.description"
    const val DC_RIGHTS = "dc.rights"
    const val DC_SOURCE = "dc.source"
    const val DC_SUBJECT = "dc.subject"
    const val DC_DATE = "dc.date"

    const val PHOTOSHOP_HEADLINE = "photoshop.headline"
    const val PHOTOSHOP_CREDIT = "photoshop.credit"
    const val PHOTOSHOP_SOURCE = "photoshop.source"
    const val PHOTOSHOP_CITY = "photoshop.city"
    const val PHOTOSHOP_STATE = "photoshop.state"
    const val PHOTOSHOP_COUNTRY = "photoshop.country"
    const val PHOTOSHOP_DATE_CREATED = "photoshop.dateCreated"
    const val PHOTOSHOP_TRANSMISSION_REFERENCE = "photoshop.transmissionReference"

    val all: List<MetadataFieldDefinition> = listOf(
        MetadataFieldDefinition(IIM_RECORD_VERSION, "Record Version", MetadataStandard.IIM_APPLICATION, "[2:0]", "4"),
        MetadataFieldDefinition(IIM_OBJECT_TYPE, "Object Type", MetadataStandard.IIM_APPLICATION, "[2:3]"),
        MetadataFieldDefinition(IIM_OBJECT_ATTRIBUTE, "Object Attribute", MetadataStandard.IIM_APPLICATION, "[2:4]"),
        MetadataFieldDefinition(IIM_OBJECT_NAME, "Object Name", MetadataStandard.IIM_APPLICATION, "[2:5]"),
        MetadataFieldDefinition(IIM_EDIT_STATUS, "Edit Status", MetadataStandard.IIM_APPLICATION, "[2:7]"),
        MetadataFieldDefinition(IIM_URGENCY, "Urgency", MetadataStandard.IIM_APPLICATION, "[2:10]"),
        MetadataFieldDefinition(IIM_CATEGORY, "Category", MetadataStandard.IIM_APPLICATION, "[2:15]"),
        MetadataFieldDefinition(IIM_SUPPLEMENTAL_CATEGORY, "Supplemental Category", MetadataStandard.IIM_APPLICATION, "[2:20]", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(IIM_KEYWORDS, "Keywords", MetadataStandard.IIM_APPLICATION, "[2:25]", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(IIM_SPECIAL_INSTRUCTIONS, "Special Instructions", MetadataStandard.IIM_APPLICATION, "[2:40]", multiline = true),
        MetadataFieldDefinition(IIM_DATE_CREATED, "Date Created", MetadataStandard.IIM_APPLICATION, "[2:55]", autofill = true),
        MetadataFieldDefinition(IIM_TIME_CREATED, "Time Created", MetadataStandard.IIM_APPLICATION, "[2:60]", autofill = true),
        MetadataFieldDefinition(IIM_JOB_IDENTIFIER, "Job Identifier", MetadataStandard.IIM_APPLICATION, "[2:103]"),
        MetadataFieldDefinition(IIM_BYLINE, "Byline", MetadataStandard.IIM_APPLICATION, "[2:80]"),
        MetadataFieldDefinition(IIM_BYLINE_TITLE, "Byline Title", MetadataStandard.IIM_APPLICATION, "[2:85]"),
        MetadataFieldDefinition(IIM_CITY, "City", MetadataStandard.IIM_APPLICATION, "[2:90]"),
        MetadataFieldDefinition(IIM_SUBLOCATION, "Sublocation", MetadataStandard.IIM_APPLICATION, "[2:92]"),
        MetadataFieldDefinition(IIM_STATE, "Province / State", MetadataStandard.IIM_APPLICATION, "[2:95]"),
        MetadataFieldDefinition(IIM_COUNTRY_CODE, "Country Code", MetadataStandard.IIM_APPLICATION, "[2:100]"),
        MetadataFieldDefinition(IIM_COUNTRY_NAME, "Country Name", MetadataStandard.IIM_APPLICATION, "[2:101]"),
        MetadataFieldDefinition(IIM_HEADLINE, "Headline", MetadataStandard.IIM_APPLICATION, "[2:105]"),
        MetadataFieldDefinition(IIM_CREDIT, "Credit", MetadataStandard.IIM_APPLICATION, "[2:110]"),
        MetadataFieldDefinition(IIM_SOURCE, "Source", MetadataStandard.IIM_APPLICATION, "[2:115]"),
        MetadataFieldDefinition(IIM_COPYRIGHT, "Copyright Notice", MetadataStandard.IIM_APPLICATION, "[2:116]"),
        MetadataFieldDefinition(IIM_CONTACT, "Contact", MetadataStandard.IIM_APPLICATION, "[2:118]"),
        MetadataFieldDefinition(IIM_CAPTION, "Caption / Abstract", MetadataStandard.IIM_APPLICATION, "[2:120]", multiline = true),
        MetadataFieldDefinition(IIM_WRITER, "Writer / Editor", MetadataStandard.IIM_APPLICATION, "[2:122]"),

        MetadataFieldDefinition(IPTC_CORE_CREATOR_CITY, "Contact Info - City", MetadataStandard.IPTC_CORE, "CreatorContactInfo/CiAdrCity"),
        MetadataFieldDefinition(IPTC_CORE_CREATOR_REGION, "Contact Info - Region", MetadataStandard.IPTC_CORE, "CreatorContactInfo/CiAdrRegion"),
        MetadataFieldDefinition(IPTC_CORE_CREATOR_COUNTRY, "Contact Info - Country", MetadataStandard.IPTC_CORE, "CreatorContactInfo/CiAdrCtry"),
        MetadataFieldDefinition(IPTC_CORE_CREATOR_COUNTRY_CODE, "Contact Info - ISO Country Code", MetadataStandard.IPTC_CORE, "CreatorContactInfo/CiAdrCtryCode"),
        MetadataFieldDefinition(IPTC_CORE_CREATOR_ADDRESS, "Contact Info - Address", MetadataStandard.IPTC_CORE, "CreatorContactInfo/CiAdrExtadr", multiline = true),
        MetadataFieldDefinition(IPTC_CORE_CREATOR_EMAIL, "Contact Info - Email", MetadataStandard.IPTC_CORE, "CreatorContactInfo/CiEmailWork"),
        MetadataFieldDefinition(IPTC_CORE_CREATOR_PHONE, "Contact Info - Phone", MetadataStandard.IPTC_CORE, "CreatorContactInfo/CiTelWork"),
        MetadataFieldDefinition(IPTC_CORE_CREATOR_WEB_URL, "Contact Info - Web URL", MetadataStandard.IPTC_CORE, "CreatorContactInfo/CiUrlWork"),
        MetadataFieldDefinition(IPTC_CORE_LOCATION, "Location", MetadataStandard.IPTC_CORE, "Iptc4xmpCore:Location"),
        MetadataFieldDefinition(IPTC_CORE_COUNTRY_CODE, "Country Code", MetadataStandard.IPTC_CORE, "Iptc4xmpCore:CountryCode"),
        MetadataFieldDefinition(IPTC_CORE_INTELLECTUAL_GENRE, "Intellectual Genre", MetadataStandard.IPTC_CORE, "Iptc4xmpCore:IntellectualGenre"),
        MetadataFieldDefinition(IPTC_CORE_SCENE, "Scene", MetadataStandard.IPTC_CORE, "Iptc4xmpCore:Scene", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(IPTC_CORE_SUBJECT_CODE, "Subject Code", MetadataStandard.IPTC_CORE, "Iptc4xmpCore:SubjectCode", valueType = MetadataFieldValueType.TEXT_LIST),

        MetadataFieldDefinition(IPTC_EXT_LOCATION_CREATED_COUNTRY_CODE, "Location Created - ISO Code", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationCreated/CountryCode"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_CREATED_COUNTRY_NAME, "Location Created - Country Name", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationCreated/CountryName"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_CREATED_REGION, "Location Created - Province / State", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationCreated/ProvinceState"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_CREATED_CITY, "Location Created - City", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationCreated/City"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_CREATED_SUBLOCATION, "Location Created - Sublocation", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationCreated/Sublocation"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE, "Location Shown - ISO Code", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationShown/CountryCode"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME, "Location Shown - Country Name", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationShown/CountryName"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_SHOWN_REGION, "Location Shown - Province / State", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationShown/ProvinceState"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_SHOWN_CITY, "Location Shown - City", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationShown/City"),
        MetadataFieldDefinition(IPTC_EXT_LOCATION_SHOWN_SUBLOCATION, "Location Shown - Sublocation", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:LocationShown/Sublocation"),
        MetadataFieldDefinition(IPTC_EXT_EVENT, "Event", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:Event"),
        MetadataFieldDefinition(IPTC_EXT_DIGITAL_SOURCE_TYPE, "Digital Source Type", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:DigitalSourceType", valueType = MetadataFieldValueType.URI),
        MetadataFieldDefinition(IPTC_EXT_PERSON_IN_IMAGE, "Person In Image", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:PersonInImage", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(IPTC_EXT_ORGANISATION_IN_IMAGE_NAME, "Organisation In Image - Name", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:OrganisationInImageName", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(IPTC_EXT_ORGANISATION_IN_IMAGE_CODE, "Organisation In Image - Code", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:OrganisationInImageCode", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(IPTC_EXT_ARTWORK_TITLE, "Artwork or Object - Title", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:ArtworkOrObject/AOTitle"),
        MetadataFieldDefinition(IPTC_EXT_ARTWORK_CREATOR, "Artwork or Object - Creator", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:ArtworkOrObject/AOCreator"),
        MetadataFieldDefinition(IPTC_EXT_ARTWORK_SOURCE, "Artwork or Object - Source", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:ArtworkOrObject/AOSource"),
        MetadataFieldDefinition(IPTC_EXT_IMAGE_CREATOR_NAME, "Image Creator - Name", MetadataStandard.IPTC_EXTENSION, "plus:ImageCreator/plus:ImageCreatorName"),
        MetadataFieldDefinition(IPTC_EXT_IMAGE_SUPPLIER_NAME, "Image Supplier - Name", MetadataStandard.IPTC_EXTENSION, "plus:ImageSupplier/plus:ImageSupplierName"),
        MetadataFieldDefinition(IPTC_EXT_IMAGE_SUPPLIER_ID, "Image Supplier - ID", MetadataStandard.IPTC_EXTENSION, "plus:ImageSupplier/plus:ImageSupplierID"),
        MetadataFieldDefinition(IPTC_EXT_REGISTRY_ENTRY_ROLE, "Registry Entry - Role", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:RegistryId/Iptc4xmpExt:RegEntryRole", valueType = MetadataFieldValueType.URI),
        MetadataFieldDefinition(IPTC_EXT_REGISTRY_ITEM_ID, "Registry Entry - Item ID", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:RegistryId/Iptc4xmpExt:RegItemId"),
        MetadataFieldDefinition(IPTC_EXT_REGISTRY_ORGANISATION_ID, "Registry Entry - Organisation ID", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:RegistryId/Iptc4xmpExt:RegOrgId"),
        MetadataFieldDefinition(IPTC_EXT_MODEL_RELEASE_STATUS, "Model Release Status", MetadataStandard.IPTC_EXTENSION, "plus:ModelReleaseStatus"),
        MetadataFieldDefinition(IPTC_EXT_MODEL_RELEASE_ID, "Model Release ID", MetadataStandard.IPTC_EXTENSION, "plus:ModelReleaseID", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(IPTC_EXT_PROPERTY_RELEASE_STATUS, "Property Release Status", MetadataStandard.IPTC_EXTENSION, "plus:PropertyReleaseStatus"),
        MetadataFieldDefinition(IPTC_EXT_PROPERTY_RELEASE_ID, "Property Release ID", MetadataStandard.IPTC_EXTENSION, "plus:PropertyReleaseID", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(IPTC_EXT_DATA_MINING, "Data Mining", MetadataStandard.IPTC_EXTENSION, "plus:DataMining", valueType = MetadataFieldValueType.URI),
        MetadataFieldDefinition(IPTC_EXT_AI_SYSTEM_USED, "AI System Used", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:AISystemUsed"),
        MetadataFieldDefinition(IPTC_EXT_AI_SYSTEM_VERSION_USED, "AI System Version Used", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:AISystemVersionUsed"),
        MetadataFieldDefinition(IPTC_EXT_AI_PROMPT, "AI Prompt Information", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:AIPromptInformation", multiline = true),
        MetadataFieldDefinition(IPTC_EXT_AI_PROMPT_WRITER, "AI Prompt Writer Name", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:AIPromptWriterName"),
        MetadataFieldDefinition(IPTC_EXT_IMAGE_REGION, "Image Region", MetadataStandard.IPTC_EXTENSION, "Iptc4xmpExt:ImageRegion", valueType = MetadataFieldValueType.STRUCT_LIST),

        MetadataFieldDefinition(PLUS_LICENSOR_NAME, "Licensor - Name", MetadataStandard.IPTC_EXTENSION, "plus:Licensor/plus:LicensorName"),
        MetadataFieldDefinition(PLUS_LICENSOR_EMAIL, "Licensor - Email", MetadataStandard.IPTC_EXTENSION, "plus:Licensor/plus:LicensorEmail"),
        MetadataFieldDefinition(PLUS_LICENSOR_URL, "Licensor - URL", MetadataStandard.IPTC_EXTENSION, "plus:Licensor/plus:LicensorURL", valueType = MetadataFieldValueType.URI),
        MetadataFieldDefinition(PLUS_LICENSOR_PHONE, "Licensor - Phone", MetadataStandard.IPTC_EXTENSION, "plus:Licensor/plus:LicensorTelephone1"),
        MetadataFieldDefinition(PLUS_COPYRIGHT_OWNER_NAME, "Copyright Owner - Name", MetadataStandard.IPTC_EXTENSION, "plus:CopyrightOwner/plus:CopyrightOwnerName"),

        MetadataFieldDefinition(DC_TITLE, "Title", MetadataStandard.DUBLIN_CORE, "dc:title", valueType = MetadataFieldValueType.LANG_ALT),
        MetadataFieldDefinition(DC_CREATOR, "Creator", MetadataStandard.DUBLIN_CORE, "dc:creator", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(DC_DESCRIPTION, "Description", MetadataStandard.DUBLIN_CORE, "dc:description", multiline = true, valueType = MetadataFieldValueType.LANG_ALT),
        MetadataFieldDefinition(DC_RIGHTS, "Rights", MetadataStandard.DUBLIN_CORE, "dc:rights", multiline = true, valueType = MetadataFieldValueType.LANG_ALT),
        MetadataFieldDefinition(DC_SOURCE, "Source", MetadataStandard.DUBLIN_CORE, "dc:source"),
        MetadataFieldDefinition(DC_SUBJECT, "Subject", MetadataStandard.DUBLIN_CORE, "dc:subject", valueType = MetadataFieldValueType.TEXT_LIST),
        MetadataFieldDefinition(DC_DATE, "Date", MetadataStandard.DUBLIN_CORE, "dc:date", autofill = true),

        MetadataFieldDefinition(PHOTOSHOP_HEADLINE, "Headline", MetadataStandard.PHOTOSHOP, "photoshop:Headline"),
        MetadataFieldDefinition(PHOTOSHOP_CREDIT, "Credit", MetadataStandard.PHOTOSHOP, "photoshop:Credit"),
        MetadataFieldDefinition(PHOTOSHOP_SOURCE, "Source", MetadataStandard.PHOTOSHOP, "photoshop:Source"),
        MetadataFieldDefinition(PHOTOSHOP_CITY, "City", MetadataStandard.PHOTOSHOP, "photoshop:City"),
        MetadataFieldDefinition(PHOTOSHOP_STATE, "State", MetadataStandard.PHOTOSHOP, "photoshop:State"),
        MetadataFieldDefinition(PHOTOSHOP_COUNTRY, "Country", MetadataStandard.PHOTOSHOP, "photoshop:Country"),
        MetadataFieldDefinition(PHOTOSHOP_DATE_CREATED, "Date Created", MetadataStandard.PHOTOSHOP, "photoshop:DateCreated", autofill = true),
        MetadataFieldDefinition(PHOTOSHOP_TRANSMISSION_REFERENCE, "Job Identifier", MetadataStandard.PHOTOSHOP, "photoshop:TransmissionReference")
    )

    val byKey: Map<String, MetadataFieldDefinition> = all.associateBy { it.key }

    val capabilities: Map<String, MetadataFieldCapability> by lazy {
        all.associate { definition ->
            definition.key to MetadataFieldCapability(
                key = definition.key,
                group = groupFor(definition.key),
                writeSupported = definition.key !in WRITE_UNSUPPORTED_FIELDS,
                presetSupported = definition.key !in PRESET_UNSUPPORTED_FIELDS && !definition.autofill,
                perPhotoSupported = definition.key in PER_PHOTO_SUPPORTED_FIELDS,
                importSupported = definition.key !in IMPORT_UNSUPPORTED_FIELDS,
                valueType = definition.valueType
            )
        }
    }

    fun isStandardField(field: String): Boolean = byKey.containsKey(field)

    fun valueType(field: String): MetadataFieldValueType =
        byKey[field]?.valueType ?: MetadataFieldValueType.TEXT

    fun capability(field: String): MetadataFieldCapability? = capabilities[field]

    fun isWriteSupported(field: String): Boolean =
        capabilities[field]?.writeSupported ?: false

    fun isPresetSupported(field: String): Boolean =
        capabilities[field]?.presetSupported ?: false

    fun isPerPhotoSupported(field: String): Boolean =
        capabilities[field]?.perPhotoSupported ?: false

    fun writableFields(): List<MetadataFieldDefinition> =
        all.filter { isWriteSupported(it.key) }

    fun presetFields(): List<MetadataFieldDefinition> =
        all.filter { isPresetSupported(it.key) }

    fun perPhotoFields(): List<MetadataFieldDefinition> =
        all.filter { isPerPhotoSupported(it.key) }

    fun fieldsInGroup(group: MetadataFieldGroup): List<MetadataFieldDefinition> =
        all.filter { capabilities[it.key]?.group == group }

    private fun groupFor(key: String): MetadataFieldGroup = when (key) {
        in BASIC_CONTENT_FIELDS -> MetadataFieldGroup.BASIC_CONTENT
        in CREATOR_CONTACT_FIELDS -> MetadataFieldGroup.CREATOR_CONTACT
        in LOCATION_FIELDS -> MetadataFieldGroup.LOCATION
        in PEOPLE_ORGANISATION_FIELDS -> MetadataFieldGroup.PEOPLE_ORGANISATION
        in RIGHTS_LICENSING_FIELDS -> MetadataFieldGroup.RIGHTS_LICENSING
        in AI_DISCLOSURE_FIELDS -> MetadataFieldGroup.AI_DISCLOSURE
        in STRUCTURED_ADVANCED_FIELDS -> MetadataFieldGroup.STRUCTURED_ADVANCED
        else -> if (key.startsWith("iim.")) MetadataFieldGroup.LEGACY_IIM else MetadataFieldGroup.ADMIN_WORKFLOW
    }

    private val BASIC_CONTENT_FIELDS = setOf(
        IIM_OBJECT_NAME,
        IIM_HEADLINE,
        IIM_CAPTION,
        IIM_KEYWORDS,
        IPTC_CORE_SCENE,
        IPTC_CORE_SUBJECT_CODE,
        DC_TITLE,
        DC_DESCRIPTION,
        DC_SUBJECT,
        PHOTOSHOP_HEADLINE
    )

    private val CREATOR_CONTACT_FIELDS = setOf(
        IIM_BYLINE,
        IIM_BYLINE_TITLE,
        IIM_CONTACT,
        IIM_WRITER,
        IPTC_CORE_CREATOR_CITY,
        IPTC_CORE_CREATOR_REGION,
        IPTC_CORE_CREATOR_COUNTRY,
        IPTC_CORE_CREATOR_COUNTRY_CODE,
        IPTC_CORE_CREATOR_ADDRESS,
        IPTC_CORE_CREATOR_EMAIL,
        IPTC_CORE_CREATOR_PHONE,
        IPTC_CORE_CREATOR_WEB_URL,
        DC_CREATOR,
        PHOTOSHOP_DATE_CREATED
    )

    private val LOCATION_FIELDS = setOf(
        IIM_CITY,
        IIM_SUBLOCATION,
        IIM_STATE,
        IIM_COUNTRY_CODE,
        IIM_COUNTRY_NAME,
        IPTC_CORE_LOCATION,
        IPTC_CORE_COUNTRY_CODE,
        IPTC_EXT_LOCATION_CREATED_COUNTRY_CODE,
        IPTC_EXT_LOCATION_CREATED_COUNTRY_NAME,
        IPTC_EXT_LOCATION_CREATED_REGION,
        IPTC_EXT_LOCATION_CREATED_CITY,
        IPTC_EXT_LOCATION_CREATED_SUBLOCATION,
        IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE,
        IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME,
        IPTC_EXT_LOCATION_SHOWN_REGION,
        IPTC_EXT_LOCATION_SHOWN_CITY,
        IPTC_EXT_LOCATION_SHOWN_SUBLOCATION,
        PHOTOSHOP_CITY,
        PHOTOSHOP_STATE,
        PHOTOSHOP_COUNTRY
    )

    private val PEOPLE_ORGANISATION_FIELDS = setOf(
        IPTC_EXT_PERSON_IN_IMAGE,
        IPTC_EXT_ORGANISATION_IN_IMAGE_NAME,
        IPTC_EXT_ORGANISATION_IN_IMAGE_CODE
    )

    private val RIGHTS_LICENSING_FIELDS = setOf(
        IIM_COPYRIGHT,
        DC_RIGHTS,
        IPTC_EXT_MODEL_RELEASE_STATUS,
        IPTC_EXT_MODEL_RELEASE_ID,
        IPTC_EXT_PROPERTY_RELEASE_STATUS,
        IPTC_EXT_PROPERTY_RELEASE_ID,
        PLUS_LICENSOR_NAME,
        PLUS_LICENSOR_EMAIL,
        PLUS_LICENSOR_URL,
        PLUS_LICENSOR_PHONE,
        PLUS_COPYRIGHT_OWNER_NAME
    )

    private val AI_DISCLOSURE_FIELDS = setOf(
        IPTC_EXT_DATA_MINING,
        IPTC_EXT_AI_SYSTEM_USED,
        IPTC_EXT_AI_SYSTEM_VERSION_USED,
        IPTC_EXT_AI_PROMPT,
        IPTC_EXT_AI_PROMPT_WRITER
    )

    private val STRUCTURED_ADVANCED_FIELDS = setOf(
        IPTC_EXT_ARTWORK_TITLE,
        IPTC_EXT_ARTWORK_CREATOR,
        IPTC_EXT_ARTWORK_SOURCE,
        IPTC_EXT_IMAGE_CREATOR_NAME,
        IPTC_EXT_IMAGE_SUPPLIER_NAME,
        IPTC_EXT_IMAGE_SUPPLIER_ID,
        IPTC_EXT_REGISTRY_ENTRY_ROLE,
        IPTC_EXT_REGISTRY_ITEM_ID,
        IPTC_EXT_REGISTRY_ORGANISATION_ID,
        IPTC_EXT_IMAGE_REGION
    )

    private val PER_PHOTO_SUPPORTED_FIELDS = setOf(
        IIM_OBJECT_NAME,
        IIM_HEADLINE,
        IIM_CAPTION,
        IIM_KEYWORDS,
        DC_TITLE,
        DC_DESCRIPTION,
        DC_SUBJECT,
        PHOTOSHOP_HEADLINE,
        IPTC_EXT_EVENT,
        IPTC_EXT_PERSON_IN_IMAGE,
        IPTC_EXT_ORGANISATION_IN_IMAGE_NAME,
        IPTC_EXT_ORGANISATION_IN_IMAGE_CODE,
        IPTC_EXT_LOCATION_SHOWN_COUNTRY_CODE,
        IPTC_EXT_LOCATION_SHOWN_COUNTRY_NAME,
        IPTC_EXT_LOCATION_SHOWN_REGION,
        IPTC_EXT_LOCATION_SHOWN_CITY,
        IPTC_EXT_LOCATION_SHOWN_SUBLOCATION
    )

    private val PRESET_UNSUPPORTED_FIELDS = setOf(
        IPTC_EXT_IMAGE_REGION
    )

    private val WRITE_UNSUPPORTED_FIELDS = emptySet<String>()

    private val IMPORT_UNSUPPORTED_FIELDS = emptySet<String>()
}
