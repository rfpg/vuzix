// Tuned hand-joint finger counting input for Spectacles.
// Requires Spectacles Interaction Kit (SIK) package in the Lens project.

//@input ScriptComponent checklistController
//@input string handSide = "right" {"widget":"combobox", "values":[{"label":"Right", "value":"right"}, {"label":"Left", "value":"left"}]}
//@input int profile = 1 {"widget":"combobox", "values":[{"label":"Near (30-45cm)", "value":0}, {"label":"Standard (45-70cm)", "value":1}, {"label":"Far (70-100cm)", "value":2}]}
//@input int stableFrameThreshold = 4
//@input bool useCustomThresholds = false
//@input float thumbRatioThreshold = 1.15
//@input float indexRatioThreshold = 1.25
//@input float middleRatioThreshold = 1.25
//@input float ringRatioThreshold = 1.22
//@input float pinkyRatioThreshold = 1.18
//@input bool logDebug = false

var SIK = require("SpectaclesInteractionKit.lspkg/SIK").SIK;

var lastRawCount = 0;
var stableCount = 0;
var stableFrames = 0;

var profileThresholds = {
    thumb: 1.15,
    index: 1.25,
    middle: 1.25,
    ring: 1.22,
    pinky: 1.18
};

function getControllerApi() {
    if (!script.checklistController || !script.checklistController.api) {
        return null;
    }
    return script.checklistController.api;
}

function setStatus(textValue) {
    var controller = getControllerApi();
    if (controller && controller.setStatus) {
        controller.setStatus(textValue);
    }
}

function setMode(textValue) {
    var controller = getControllerApi();
    if (controller && controller.setMode) {
        controller.setMode(textValue);
    }
}

function setChecklistCount(value) {
    var controller = getControllerApi();
    if (controller && controller.setCount) {
        controller.setCount(value);
    }
}

function getDistance(pointA, pointB) {
    return pointA.distance(pointB);
}

function isFingerExtended(tipPosition, knucklePosition, wristPosition, ratioThreshold) {
    var tipToWrist = getDistance(tipPosition, wristPosition);
    var knuckleToWrist = getDistance(knucklePosition, wristPosition);

    if (knuckleToWrist <= 0.0001) {
        return false;
    }

    return (tipToWrist / knuckleToWrist) > ratioThreshold;
}

function applyPresetThresholds() {
    if (script.useCustomThresholds) {
        profileThresholds.thumb = script.thumbRatioThreshold;
        profileThresholds.index = script.indexRatioThreshold;
        profileThresholds.middle = script.middleRatioThreshold;
        profileThresholds.ring = script.ringRatioThreshold;
        profileThresholds.pinky = script.pinkyRatioThreshold;
        return;
    }

    if (script.profile === 0) {
        profileThresholds.thumb = 1.10;
        profileThresholds.index = 1.18;
        profileThresholds.middle = 1.18;
        profileThresholds.ring = 1.16;
        profileThresholds.pinky = 1.12;
        return;
    }

    if (script.profile === 2) {
        profileThresholds.thumb = 1.20;
        profileThresholds.index = 1.30;
        profileThresholds.middle = 1.30;
        profileThresholds.ring = 1.27;
        profileThresholds.pinky = 1.23;
        return;
    }

    profileThresholds.thumb = 1.15;
    profileThresholds.index = 1.25;
    profileThresholds.middle = 1.25;
    profileThresholds.ring = 1.22;
    profileThresholds.pinky = 1.18;
}

function profileName() {
    if (script.useCustomThresholds) {
        return "Custom";
    }
    if (script.profile === 0) {
        return "Near";
    }
    if (script.profile === 2) {
        return "Far";
    }
    return "Standard";
}

function estimateFingerCount(hand) {
    var wrist = hand.wrist.position;

    var thumbExtended = isFingerExtended(
        hand.thumbTip.position,
        hand.thumbKnuckle.position,
        wrist,
        profileThresholds.thumb
    );

    var indexExtended = isFingerExtended(
        hand.indexTip.position,
        hand.indexKnuckle.position,
        wrist,
        profileThresholds.index
    );

    var middleExtended = isFingerExtended(
        hand.middleTip.position,
        hand.middleKnuckle.position,
        wrist,
        profileThresholds.middle
    );

    var ringExtended = isFingerExtended(
        hand.ringTip.position,
        hand.ringKnuckle.position,
        wrist,
        profileThresholds.ring
    );

    var pinkyExtended = isFingerExtended(
        hand.pinkyTip.position,
        hand.pinkyKnuckle.position,
        wrist,
        profileThresholds.pinky
    );

    var count = 0;
    if (thumbExtended) { count += 1; }
    if (indexExtended) { count += 1; }
    if (middleExtended) { count += 1; }
    if (ringExtended) { count += 1; }
    if (pinkyExtended) { count += 1; }

    return count;
}

function applySmoothing(rawCount) {
    if (rawCount === lastRawCount) {
        stableFrames += 1;
    } else {
        lastRawCount = rawCount;
        stableFrames = 1;
    }

    if (stableFrames >= script.stableFrameThreshold) {
        stableCount = rawCount;
    }

    return stableCount;
}

function onUpdate() {
    var controller = getControllerApi();
    if (!controller) {
        return;
    }

    var hand = null;
    try {
        hand = SIK.HandInputData.getHand(script.handSide);
    } catch (error) {
        setStatus("SIK hand input unavailable");
        return;
    }

    if (!hand || !hand.isTracked || !hand.isTracked()) {
        setStatus("Hand not tracked");
        return;
    }

    var rawCount = estimateFingerCount(hand);
    var filteredCount = applySmoothing(rawCount);

    setChecklistCount(filteredCount);
    setMode("Mode: SIK Finger Count (" + profileName() + ")");
    setStatus("Finger count: " + filteredCount);

    if (script.logDebug) {
        print(
            "Profile=" + profileName() +
            " raw=" + rawCount +
            " filtered=" + filteredCount +
            " stableFrames=" + stableFrames +
            " thresholds=[" +
            profileThresholds.thumb + "," +
            profileThresholds.index + "," +
            profileThresholds.middle + "," +
            profileThresholds.ring + "," +
            profileThresholds.pinky + "]"
        );
    }
}

function initialize() {
    applyPresetThresholds();
    setMode("Mode: SIK Finger Count (" + profileName() + ")");
    setStatus("Waiting for hand tracking");
}

script.createEvent("OnStartEvent").bind(initialize);
script.createEvent("UpdateEvent").bind(onUpdate);
