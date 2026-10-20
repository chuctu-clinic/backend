import mongoose from 'mongoose';

const checkinSchema = new mongoose.Schema({
  appointmentId: { type: mongoose.Schema.Types.ObjectId, ref: 'Appointment', required: true },
  patientId: { type: mongoose.Schema.Types.ObjectId, ref: 'Patient', required: true },
  visitDate: { type: String, required: true }, // YYYY-MM-DD theo giờ Việt Nam
  checkInTime: { type: Date, required: true },
  priorityGroup: { type: String, enum: ['PRIORITY', 'NORMAL'] as const, required: true },
  status: { type: String, enum: ['WAITING', 'IN_ROOM', 'COMPLETED', 'REVERTED'] as const, default: 'WAITING' },
  seatNo: { type: Number, min: 1, max: 5, default: null },
  enteredAt: { type: Date, default: null },
  completedAt: { type: Date, default: null },
  createdAt: { type: Date, default: Date.now },
});

// Mỗi phiếu có tối đa một check-in đang hiệu lực
checkinSchema.index(
  { appointmentId: 1 },
  { unique: true, partialFilterExpression: { status: { $in: ['WAITING', 'IN_ROOM', 'COMPLETED'] } } },
);
// Một ghế chỉ có một người trong phòng khám tại một thời điểm
checkinSchema.index({ visitDate: 1, seatNo: 1 }, { unique: true, partialFilterExpression: { status: 'IN_ROOM' } });
checkinSchema.index({ visitDate: 1, status: 1, checkInTime: 1 });

export const Checkin = mongoose.model('Checkin', checkinSchema, 'checkins');
export type CheckinDoc = ReturnType<(typeof Checkin)['hydrate']>;
