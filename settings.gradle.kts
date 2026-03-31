plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
    id("org.ajoberstar.reckon.settings") version "2.0.0"
}

rootProject.name = "primme-ffm-java"

reckon {
    setDefaultInferredScope("patch")
    setScopeCalc(calcScopeFromProp())
    snapshots()
    stages("beta", "final")
    setStageCalc(calcStageFromProp())
}
