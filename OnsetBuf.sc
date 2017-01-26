// contains one recording, pos 0 and the last frame
SimpleLoopBuf {
	var <>in;
	var <server;
	var <>defBufLen;
	var <buffer;
	var <list;
	var <synth;
	var <isRecording = false;

	*new {arg in=0, server, defBufLen=10;
		^super.newCopyArgs(in, server, defBufLen).init;
	}

	init {
		server ?? {server = Server.default};
	}

	rec {arg clock, quant;
		if (isRecording) {
			synth.release;
			isRecording = false;
		} {
			if (clock.notNil) {
				clock.play({
					this.pr_startRec;
				}, quant);
			} {
				this.pr_startRec;
			};
			isRecording = true;
		}
	}

	free {
		buffer.free;
	}

	pr_startRec {
		var buf = Buffer.alloc(
			server,
			server.sampleRate * defBufLen,
			2,
			{arg bfr; synth = Synth('simple-buf-rec', ['buf', bfr, 'in', in, 'thr', 0.05])}
		);
		buffer = buf;
		list = [0, nil];
	}

	*initClass {
		ServerBoot.add({
			SynthDef('simple-buf-rec', {arg buf, in=0, thr=0.05, gate=1;
				var env, sig, amp, trig, phase;

				env = EnvGen.ar(Env.asr(0.05), gate, doneAction:2); // global gate
				sig = SoundIn.ar([in, in+1]) * gate;

				phase = Phasor.ar(end:BufFrames.kr(buf));
				BufWr.ar(sig, buf, phase, 0);
			}).add;
		});
	}
}

OnsetBuf : SimpleLoopBuf {
	var <oscresp;

	pr_startRec {
		var buf = Buffer.alloc(
			server,
			server.sampleRate * defBufLen,
			2,
			{arg bfr; synth = Synth('onset-buf-rec', ['buf', bfr, 'in', in, 'thr', 0.05])}
		);
		this.pr_addOSC;
		buffer = buf;
		list = Array.new(20);
	}

	pr_addOSC {
		oscresp = OSCFunc({arg msg;
			if (msg[2] == 99) {
				list = list.add(msg[3]);
				msg.postln;
			}
		}, '\tr', server.addr);
	}

	pr_freeResources {
		synth.release;
		oscresp.free;
	}

	free {
		buffer.free;
		oscresp.free;
	}

	*initClass {
		ServerBoot.add({
			SynthDef('onset-buf-rec', {arg buf, in=0, thr=0.05, gate=1;
				var env, sig, amp, trig, phase;

				env = EnvGen.ar(Env.asr(0.1), gate, doneAction:2); // global gate
				sig = SoundIn.ar([in, in+1]) * gate;

				phase = Phasor.ar(end:BufFrames.kr(buf));
				BufWr.ar(sig, buf, phase, 0);

				SendTrig.kr(
					Coyote.kr(Mix.new(sig),
						fastMul:0.6,
						thresh:thr),
					99,
					phase
				);
			}).add;
		});
	}
}

RandOnsetBuf : OnsetBuf {

	// newUsing(buffer, numOnsets) adds a random list of onsets and the buffer
}


// just loop a simple loop buf and sync nicely
SimpleLoopBufPlayer {
	var <>defaultQuant; // TODO: MAKE SETTER THAT APPLIES THIS TO ALL RELEVANT PATTERNPROXIES
	var <server;
	var <obj;
	var <durs;
	var <player;
	var <proxy;

	*new  {arg defaultQuant=4, server;
		^super.newCopyArgs(defaultQuant, server).init;
	}

	init {
		server ?? {server = Server.default};
		proxy = PbindProxy ('instrument', 'simpleplayer');
	}

	// give it an OnsetBuf
	play {arg obj, clock, quant, amp=1.0;
		var durs, dec;

		clock = clock ?? {TempoClock.default};
		quant = quant ?? {defaultQuant}; // if no quant argument is provided default is used
		proxy.set(
			'dur', defaultQuant,
			'legato', 1,
			'buf', obj.buffer,
			'amp', amp
		);
		player = proxy.play(clock, quant:quant);
	}

	// Proxy stream goes on. Todo: make it start/stoppable at will (figure out how player works here)
	stop {
		player.stop;
	}

	// silence
	hush {
		proxy.set('amp', 0.0);
	}

	// TODO: IMPROVE DOUBLE TRIGGER LOOP OR WHAT?
	*initClass {
		ServerBoot.add({
			// Unexpected results, unless recorded portions are longer than dur
			SynthDef('simpleplayer', {arg buf, pos=0, gate=1, amp=1.0;
				var env, sig, phase;
				env = EnvGen.ar(Env.asr(0.01), gate, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * Lag.kr(amp);
				Out.ar(0, sig);
			}).add;
		});
	}
}


// A player that plays, shuffles and bends one OnsetBuf
OnsetBufPlayer {
	var <>defaultQuant; // TODO: MAKE SETTER THAT APPLIES THIS TO ALL RELEVANT PATTERNPROXIES
	var <server;
	var <obj;
	var <durs;
	var <>teiler=2;  // 1/teiler=dur
	var <player;
	var <proxy;

	*new  {arg defaultQuant=4, server;
		^super.newCopyArgs(defaultQuant, server).init;
	}

	init {
		server ?? {server = Server.default};
		proxy = PbindProxy ('instrument', 'grainplayer');
	}

	// give it an OnsetBuf
	play {arg obj, clock, quant, amp=1.0;
		var durs, dec;

		durs = Array.fill(obj.list.size, { 1/teiler});
		clock = clock ?? {TempoClock.default};
		quant = quant ?? {defaultQuant}; // if no quant argument is provided default is used
		dec = (clock.tempo * teiler).reciprocal; // actual seconds. Beware of tempo changes!!
		proxy.set(
			'dur', Pseq(durs, inf),
			'dec', dec,
			'pos', Pseq(obj.list, inf),
			'buf', obj.buffer,
			'amp', amp
		);
		player = proxy.play(clock, quant:quant);
	}

	// Proxy stream goes on. Todo: make it start/stoppable at will (figure out how player works here)
	stop {
		player.stop;
	}

	// silence
	hush {
		proxy.set('amp', 0.0);
	}

	// make a rhythm
	rh {arg numBeats ... args;
		var arr;
		args = ((args - 1) * teiler).asFloat;
		arr = Array.fill(teiler * numBeats, {arg i;
			if (args.includes(i.asFloat)) {
				1.0
			} {
				0.0
			};
		});
		proxy.at(\amp).quant = defaultQuant; // so the changes apply at next "bar"
		proxy.at(\amp).source = Pseq(arr, inf); // set the source of PatternProxy
	}

	set {arg key, val, quant;
		quant ?? {proxy.at(key).quant = quant};
		proxy.at(key).source = val;
	}

	// TODO: IMPROVE DOUBLE TRIGGER LOOP OR WHAT?
	*initClass {
		ServerBoot.add({
			// Unexpected results, unless recorded portions are longer than dur
			SynthDef('onsetplayer', {arg buf, pos=0, gate=1, amp=1.0;
				var env, sig, phase;
				env = EnvGen.ar(Env.asr(0.01), gate, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * amp;
				Out.ar(0, sig);
			}).add;

			// grain player with auto release
			SynthDef('grainplayer', {arg buf, pos=0, att=0.01, dec=0.3, amp=1.0;
				var env, sig, phase;
				env = EnvGen.ar(Env.perc(att, dec), 1, doneAction:2);
				phase = Phasor.ar(start:pos, end:BufFrames.kr(buf));
				sig = BufRd.ar(2, buf, phase, loop:0) * env * amp;
				Out.ar(0, sig);
			}).add;
		});
	}
}