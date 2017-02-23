// LoopClock which inherits from TempoClock and has beatsPerBar as an additional argument
LoopClock : TempoClock {

	var secondsLastTap=0;
	var midiFunc;
	var <>undoObj;

	*new { arg tempo, beats=0, seconds, queueSize=256, beatsPerBar=4.0;
		^super.new.initLoopClock(tempo, beats, seconds, queueSize, beatsPerBar)
	}

	initLoopClock { arg tempo, beats, seconds, queueSize, bpb;
		beatsPerBar = bpb;
		barsPerBeat = bpb.reciprocal;
		queue = Array.new(queueSize);
		this.prStart(tempo, beats, seconds);
		all = all.add(this);
	}

	stop {
		this.changed(\stop);
		this.releaseDependants;
		this.removeMidiFunc;
		undoObj = nil;
		all.take(this);
		this.prStop;
	}

	// add midi tap tempo functionality
	addMidiFunc {arg note=60, chan=14;
		midiFunc = MIDIFunc.noteOn({
			this.tap;
		}, note, chan);
	}

	removeMidiFunc {
		midiFunc.free;
	}

	// tapTempo
	tap {
		if (secondsLastTap + 3 > this.seconds) {
			this.tempo = (this.seconds - secondsLastTap).reciprocal;
			if (undoObj.notNil) {undoObj.put([this.tempo, \tempo])};
		};
		secondsLastTap = this.seconds;
	}
}

// makes TempoClocks from start and stop calls calculating the tempo from the
// time difference and beats argument
ClockMaker {
	classvar start;
	classvar <state=0;

	*start {
		start = thisThread.seconds;
		state = 1;
	}

	*stop {arg beats=4.0;
		if (state != 1) {("start must be called first.").inform; ^nil};
		state = 0;
		^LoopClock(((thisThread.seconds - start) / beats).reciprocal, beatsPerBar:beats);
	}
}