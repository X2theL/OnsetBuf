// store any object here for n undo levels
// in ss-onset these are arrays of values, types and indices
Undo {

	var <list;
	var pointer;

	*new {arg levels=3;
		^super.new.init(levels);
	}

	init {arg lvl;
		list = Array.newClear((lvl * 2) + 1);
		pointer = lvl; // the center of the Array
	}

	put {arg obj;
		if (list.first.notNil) {
			list.first.tryPerform(\free);
		};
		list = list.shift(-1, nil);
		if (list.at(pointer).notNil) {list.at(pointer).tryPerform(\free)};
		list.put(pointer, obj);
	}

	undo {
		if(list.at(pointer - 1).isNil) {^nil};
		list = list.shift(1, nil);
		^list.at(pointer);
	}

	redo {
		if(list.at(pointer + 1).isNil) {^nil};
		list = list.shift(-1, nil);
		^list.at(pointer);
	}

	free {
		list.do {arg i;
			i.tryPerform(\free);
		}
	}
}
