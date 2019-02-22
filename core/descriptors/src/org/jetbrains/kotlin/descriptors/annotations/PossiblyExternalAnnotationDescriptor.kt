package org.jetbrains.kotlin.descriptors.annotations

interface PossiblyExternalAnnotationDescriptor : AnnotationDescriptor {
    val isIdeExternalAnnotation: Boolean
}