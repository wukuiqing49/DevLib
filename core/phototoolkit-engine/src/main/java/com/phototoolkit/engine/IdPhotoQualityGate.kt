package com.phototoolkit.engine

data class IdPhotoSourceQuality(
    val sourceHeadHeightPx: Double,
    val targetHeadHeightPx: Double,
    val requiredScale: Double,
    val accepted: Boolean,
)

object IdPhotoSourceQualityGate {
    fun evaluate(
        head: PortraitHeadGeometry,
        outputHeight: Int,
        targetHeadHeightRatio: Double,
        maxUpscaleRatio: Double,
    ): IdPhotoSourceQuality {
        require(outputHeight > 0)
        val target = outputHeight * targetHeadHeightRatio
        val scale = target / head.height
        return IdPhotoSourceQuality(
            sourceHeadHeightPx = head.height,
            targetHeadHeightPx = target,
            requiredScale = scale,
            accepted = scale <= maxUpscaleRatio,
        )
    }
}

object IdPhotoFinalValidator {
    fun validate(
        context: android.content.Context,
        outputPath: String,
        spec: IdPhotoSpec,
        backgroundColor: IdPhotoColor,
        outputFormat: IdPhotoOutputFormat,
        predictedShoulderLineRatio: Double?,
    ): Pair<PortraitFace, IdPhotoComplianceReport> {
        val detection = PortraitFaceDetector.detect(context, outputPath, minFaceSize = 0.08f)
        val subjectFaces = detection.subjectFaces()
        require(subjectFaces.size == 1) { "FINAL_FACE_COUNT_INVALID" }
        val face = subjectFaces.single()
        require(face.hasBothEyes && face.chinY != null) { "FINAL_FACE_GEOMETRY_INVALID" }
        val mask = PortraitMatting.createMask(
            context.applicationContext,
            outputPath,
            face,
            detection.imageWidth,
            detection.imageHeight,
        )
        val head = PortraitHeadGeometryEstimator.estimate(
            face = face,
            mask = mask,
            imageWidth = detection.imageWidth,
            imageHeight = detection.imageHeight,
            measurement = spec.headMeasurement,
        )
        require(head.isReliable) { "FINAL_HEAD_GEOMETRY_UNRELIABLE" }
        val actualPlan = IdPhotoComplianceEvaluator
            .actualPlan(head, detection.imageWidth, detection.imageHeight)
            .copy(predictedShoulderLineRatio = predictedShoulderLineRatio)
        val report = IdPhotoComplianceEvaluator.evaluate(
            spec = spec,
            face = face,
            plan = actualPlan,
            backgroundColor = backgroundColor,
            outputFormat = outputFormat,
            outputBytes = java.io.File(outputPath).length(),
            outputWidth = detection.imageWidth,
            outputHeight = detection.imageHeight,
        )
        require(report.canExport) { "FINAL_HARD_CONSTRAINT_FAILED" }
        return face to report
    }
}
